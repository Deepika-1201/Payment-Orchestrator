ALTER TABLE routing_rules DROP CONSTRAINT routing_rules_strategy_check;
ALTER TABLE routing_rules ADD CONSTRAINT routing_rules_strategy_check
    CHECK (strategy IN ('PRIORITY', 'WEIGHTED', 'DYNAMIC', 'COST', 'ADAPTIVE'));
ALTER TABLE routing_rules ADD COLUMN shadow_strategy text
    CHECK (shadow_strategy IN ('COST', 'ADAPTIVE'));

CREATE TABLE provider_fee_schedules (
    id              text PRIMARY KEY,
    merchant_id     text        NOT NULL,
    provider_code   text        NOT NULL,
    method          text        NOT NULL CHECK (method IN ('UPI', 'CARD', 'NETBANKING', 'MANDATE', 'WALLET', 'EMI',
                                                           'CARDLESS_EMI', 'PAY_LATER', 'BANK_TRANSFER')),
    currency        char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    fixed_amount    bigint      NOT NULL CHECK (fixed_amount BETWEEN 0 AND 1000000000000),
    rate_bps        integer     NOT NULL CHECK (rate_bps BETWEEN 0 AND 10000),
    tax_bps         integer     NOT NULL CHECK (tax_bps BETWEEN 0 AND 10000),
    collection      text        NOT NULL CHECK (collection IN ('WITHHELD', 'POSTPAID')),
    effective_at    timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT fk_fee_schedule_account FOREIGN KEY (merchant_id, provider_code)
        REFERENCES merchant_provider_accounts (merchant_id, provider_code),
    CONSTRAINT ux_fee_schedule_effective UNIQUE (merchant_id, provider_code, method, currency, effective_at),
    CONSTRAINT ck_fee_schedule_not_backdated CHECK (effective_at >= created_at)
);
CREATE TRIGGER trg_fee_schedules_append_only
    BEFORE UPDATE OR DELETE ON provider_fee_schedules
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE routing_decisions (
    attempt_id          text PRIMARY KEY REFERENCES payment_attempts (id),
    merchant_id         text        NOT NULL REFERENCES merchants (id),
    rule_id             text,
    method              text        NOT NULL,
    bank_code           text,
    live_strategy       text        NOT NULL CHECK (live_strategy IN ('PRIORITY', 'WEIGHTED', 'DYNAMIC', 'COST', 'ADAPTIVE')),
    chosen_provider     text        NOT NULL,
    live_evaluation     jsonb       NOT NULL,
    shadow_strategy     text        CHECK (shadow_strategy IN ('COST', 'ADAPTIVE')),
    shadow_provider     text,
    shadow_evaluation   jsonb,
    evaluated_at        timestamptz NOT NULL,
    CONSTRAINT ck_routing_shadow CHECK ((shadow_strategy IS NULL) = (shadow_evaluation IS NULL)
                                       AND (shadow_strategy IS NULL) = (shadow_provider IS NULL))
);
CREATE INDEX ix_routing_decisions_merchant ON routing_decisions (merchant_id, evaluated_at DESC, attempt_id);
CREATE INDEX ix_routing_decisions_retention ON routing_decisions (evaluated_at);
CREATE TRIGGER trg_routing_decisions_no_update
    BEFORE UPDATE ON routing_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

ALTER TABLE reconciliation_exceptions DROP CONSTRAINT reconciliation_exceptions_type_check;
ALTER TABLE reconciliation_exceptions ADD CONSTRAINT reconciliation_exceptions_type_check
    CHECK (type IN ('MISSING_INTERNALLY', 'MISSING_AT_PROVIDER', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'DUPLICATE',
                    'SETTLEMENT_MISMATCH', 'UNMATCHED_ADJUSTMENT', 'CONVERSION_MISSING', 'FEE_SCHEDULE_MISMATCH'));