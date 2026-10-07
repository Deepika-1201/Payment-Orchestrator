-- Recurring payments: mandates and their debits (requirements §8.1, NFR-19, ADR-035).

CREATE TABLE mandates (
    id                           text PRIMARY KEY,
    merchant_id                  text        NOT NULL REFERENCES merchants (id),
    provider_code                text        NOT NULL,
    instrument                   text        NOT NULL CHECK (instrument IN ('UPI_AUTOPAY', 'CARD', 'ENACH')),
    status                       text        NOT NULL CHECK (status IN ('CREATED', 'PENDING_AUTHORIZATION', 'ACTIVE', 'PAUSED',
                                                                       'REVOKED', 'EXPIRED', 'FAILED')),
    max_amount                   bigint      NOT NULL CHECK (max_amount > 0),
    currency                     char(3)     NOT NULL,
    frequency                    text        NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'FORTNIGHTLY', 'MONTHLY',
                                                                          'BIMONTHLY', 'QUARTERLY', 'HALF_YEARLY',
                                                                          'YEARLY', 'AS_PRESENTED')),
    start_at                     timestamptz NOT NULL,
    end_at                       timestamptz NOT NULL,
    description                  text,
    customer_reference           text,
    customer_name                text,
    customer_email               text        NOT NULL,
    customer_phone               text        NOT NULL,
    metadata                     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- No foreign key: the payment references the mandate, and both are written in one transaction.
    registration_payment_id      text,
    provider_reference           text,
    provider_mandate_reference   text,
    provider_customer_reference  text,
    next_action                  jsonb,
    authorization_expires_at     timestamptz NOT NULL,
    next_check_at                timestamptz,
    check_count                  integer     NOT NULL DEFAULT 0,
    failure_code                 text,
    failure_message              text,
    activated_at                 timestamptz,
    version                      bigint      NOT NULL,
    created_at                   timestamptz NOT NULL,
    updated_at                   timestamptz NOT NULL,
    CONSTRAINT ck_mandates_term CHECK (end_at > start_at)
);
CREATE INDEX ix_mandates_merchant ON mandates (merchant_id, created_at DESC);
-- Scoped by merchant so one merchant's PSP account cannot claim another merchant's mandate (ADR-014).
CREATE UNIQUE INDEX ux_mandates_provider_ref ON mandates (provider_code, merchant_id, provider_reference)
    WHERE provider_reference IS NOT NULL;
CREATE UNIQUE INDEX ux_mandates_provider_mandate_ref ON mandates (provider_code, merchant_id, provider_mandate_reference)
    WHERE provider_mandate_reference IS NOT NULL;
CREATE INDEX ix_mandates_check ON mandates (next_check_at) WHERE next_check_at IS NOT NULL;

ALTER TABLE payments ADD COLUMN mandate_id text REFERENCES mandates (id);
ALTER TABLE payments ADD COLUMN attempt_limit integer CHECK (attempt_limit > 0);
CREATE INDEX ix_payments_mandate ON payments (mandate_id) WHERE mandate_id IS NOT NULL;

ALTER TABLE payment_attempts DROP CONSTRAINT payment_attempts_method_type_check;
ALTER TABLE payment_attempts ADD CONSTRAINT payment_attempts_method_type_check
    CHECK (method_type IN ('UPI', 'CARD', 'NETBANKING', 'MANDATE'));

-- Frictionless (no additional factor) debit limit for this merchant's card and UPI mandates; NULL = the default.
ALTER TABLE merchants ADD COLUMN mandate_debit_limit bigint CHECK (mandate_debit_limit BETWEEN 100 AND 10000000);

CREATE TABLE mandate_debits (
    id                         text PRIMARY KEY,
    mandate_id                 text        NOT NULL REFERENCES mandates (id),
    merchant_id                text        NOT NULL,
    payment_id                 text        NOT NULL UNIQUE REFERENCES payments (id),
    merchant_debit_id          text        NOT NULL,
    amount                     bigint      NOT NULL CHECK (amount > 0),
    currency                   char(3)     NOT NULL,
    max_amount                 bigint      NOT NULL,
    frictionless_limit         bigint,
    requires_notification      boolean     NOT NULL,
    status                     text        NOT NULL CHECK (status IN ('SCHEDULED', 'NOTIFYING', 'READY', 'EXECUTING',
                                                                     'SUCCEEDED', 'FAILED', 'CANCELLED')),
    description                text,
    due_at                     timestamptz NOT NULL,
    not_before                 timestamptz NOT NULL,
    cycle                      integer     NOT NULL CHECK (cycle > 0),
    notification_reference     text,
    notification_requested_at  timestamptz,
    notified_at                timestamptz,
    next_action_at             timestamptz,
    check_count                integer     NOT NULL DEFAULT 0,
    last_executed_at           timestamptz,
    failure_code               text,
    failure_message            text,
    version                    bigint      NOT NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    CONSTRAINT ux_mandate_debits_merchant_ref UNIQUE (mandate_id, merchant_debit_id),
    CONSTRAINT ck_mandate_debits_within_max CHECK (amount <= max_amount),
    CONSTRAINT ck_mandate_debits_frictionless CHECK (frictionless_limit IS NULL OR amount <= frictionless_limit),
    -- NFR-19: a notified cycle executes no earlier than 24 hours after its notification was delivered.
    CONSTRAINT ck_mandate_debits_notice CHECK (
        CASE WHEN requires_notification AND last_executed_at IS NOT NULL
             THEN coalesce(last_executed_at >= notified_at + interval '24 hours', false)
             ELSE true
        END)
);
CREATE UNIQUE INDEX ux_mandate_debits_in_progress ON mandate_debits (mandate_id)
    WHERE status IN ('SCHEDULED', 'NOTIFYING', 'READY', 'EXECUTING');
CREATE INDEX ix_mandate_debits_mandate ON mandate_debits (mandate_id, created_at);
CREATE INDEX ix_mandate_debits_due ON mandate_debits (next_action_at) WHERE next_action_at IS NOT NULL;
CREATE INDEX ix_mandate_debits_notification ON mandate_debits (notification_reference)
    WHERE notification_reference IS NOT NULL;

-- NFR-19: amounts are fixed at creation, max_amount is the mandate's, and only an ACTIVE mandate's debit executes.
CREATE FUNCTION mandate_debits_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    mandate_status     text;
    mandate_max_amount bigint;
BEGIN
    SELECT status, max_amount INTO mandate_status, mandate_max_amount FROM mandates WHERE id = NEW.mandate_id;
    IF TG_OP = 'INSERT' AND NEW.max_amount IS DISTINCT FROM mandate_max_amount THEN
        RAISE EXCEPTION 'debit % must carry the max_amount of mandate %', NEW.id, NEW.mandate_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.amount IS DISTINCT FROM OLD.amount OR NEW.max_amount IS DISTINCT FROM OLD.max_amount
                             OR NEW.frictionless_limit IS DISTINCT FROM OLD.frictionless_limit) THEN
        RAISE EXCEPTION 'the amounts of debit % cannot change', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.last_executed_at IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.last_executed_at IS DISTINCT FROM OLD.last_executed_at)
       AND mandate_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'mandate % is %, so debit % cannot execute', NEW.mandate_id, mandate_status, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_mandate_debits_guard
    BEFORE INSERT OR UPDATE ON mandate_debits
    FOR EACH ROW EXECUTE FUNCTION mandate_debits_guard();

CREATE TABLE mandate_transitions (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mandate_id   text        NOT NULL,
    merchant_id  text        NOT NULL,
    entity       text        NOT NULL CHECK (entity IN ('MANDATE', 'DEBIT')),
    entity_id    text        NOT NULL,
    from_status  text,
    to_status    text        NOT NULL,
    source       text        NOT NULL,
    reason       text,
    occurred_at  timestamptz NOT NULL
);
CREATE INDEX ix_mandate_transitions_mandate ON mandate_transitions (mandate_id, id);

CREATE TRIGGER trg_mandate_transitions_append_only
    BEFORE UPDATE OR DELETE ON mandate_transitions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
