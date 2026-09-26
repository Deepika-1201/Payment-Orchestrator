-- Core schema for the payment gateway (V1).
-- Amounts are integers in minor units; all timestamps are UTC (timestamptz) supplied by the application clock.

CREATE TABLE merchants (
    id                      text PRIMARY KEY,
    name                    text        NOT NULL,
    status                  text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    webhook_url             text,
    webhook_secret_enc      bytea,
    late_success_policy     text        NOT NULL CHECK (late_success_policy IN ('AUTO_REFUND', 'ACCEPT')),
    payment_expiry_seconds  integer     NOT NULL CHECK (payment_expiry_seconds BETWEEN 60 AND 86400),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL
);

CREATE TABLE api_keys (
    id           text PRIMARY KEY,
    merchant_id  text        NOT NULL REFERENCES merchants (id),
    key_hash     bytea       NOT NULL UNIQUE,
    key_hint     text        NOT NULL,
    mode         text        NOT NULL CHECK (mode IN ('TEST', 'LIVE')),
    status       text        NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);
CREATE INDEX ix_api_keys_merchant ON api_keys (merchant_id);

CREATE TABLE merchant_provider_accounts (
    id               text PRIMARY KEY,
    merchant_id      text        NOT NULL REFERENCES merchants (id),
    provider_code    text        NOT NULL,
    status           text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    credentials_enc  bytea,
    created_at       timestamptz NOT NULL,
    CONSTRAINT ux_merchant_provider UNIQUE (merchant_id, provider_code)
);

CREATE TABLE payments (
    id                        text PRIMARY KEY,
    merchant_id               text        NOT NULL REFERENCES merchants (id),
    merchant_order_id         text        NOT NULL,
    amount                    bigint      NOT NULL CHECK (amount > 0),
    currency                  char(3)     NOT NULL,
    status                    text        NOT NULL CHECK (status IN ('REQUIRES_PAYMENT_METHOD', 'PROCESSING', 'REQUIRES_ACTION',
                                                                     'AUTHORIZED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    capture_method            text        NOT NULL CHECK (capture_method IN ('AUTOMATIC', 'MANUAL')),
    description               text,
    customer_reference        text,
    customer_email            text,
    customer_phone            text,
    metadata                  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    amount_captured           bigint      NOT NULL DEFAULT 0 CHECK (amount_captured >= 0),
    amount_refunded           bigint      NOT NULL DEFAULT 0 CHECK (amount_refunded >= 0),
    succeeded_attempt_id      text,
    cancellation_reason       text,
    failure_code              text,
    failure_message           text,
    expires_at                timestamptz NOT NULL,
    authorization_expires_at  timestamptz,
    version                   bigint      NOT NULL,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    CONSTRAINT ck_payments_captured_le_amount CHECK (amount_captured <= amount),
    CONSTRAINT ck_payments_refunded_le_captured CHECK (amount_refunded <= amount_captured)
);
CREATE INDEX ix_payments_merchant_created ON payments (merchant_id, created_at DESC);
CREATE INDEX ix_payments_merchant_order ON payments (merchant_id, merchant_order_id);
CREATE INDEX ix_payments_customer ON payments (merchant_id, customer_reference, created_at) WHERE customer_reference IS NOT NULL;
CREATE INDEX ix_payments_expiry ON payments (expires_at) WHERE status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING');
CREATE INDEX ix_payments_auth_expiry ON payments (authorization_expires_at) WHERE status = 'AUTHORIZED';

CREATE TABLE payment_attempts (
    id                    text PRIMARY KEY,
    payment_id            text        NOT NULL REFERENCES payments (id),
    merchant_id           text        NOT NULL,
    attempt_number        integer     NOT NULL CHECK (attempt_number > 0),
    provider_code         text        NOT NULL,
    method_type           text        NOT NULL CHECK (method_type IN ('UPI', 'CARD', 'NETBANKING')),
    method_details        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    amount                bigint      NOT NULL CHECK (amount > 0),
    currency              char(3)     NOT NULL,
    status                text        NOT NULL CHECK (status IN ('INITIATED', 'REQUIRES_ACTION', 'PENDING', 'UNKNOWN', 'AUTHORIZED',
                                                                 'CAPTURE_PENDING', 'SUCCEEDED', 'FAILED', 'VOIDED')),
    provider_reference    text,
    next_action           jsonb,
    failure_code          text,
    failure_category      text,
    failure_message       text,
    routing_rule_id       text,
    authorized_at         timestamptz,
    captured_at           timestamptz,
    void_requested        boolean     NOT NULL DEFAULT false,
    next_status_check_at  timestamptz,
    status_check_count    integer     NOT NULL DEFAULT 0,
    needs_review          boolean     NOT NULL DEFAULT false,
    version               bigint      NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT ux_attempt_number UNIQUE (payment_id, attempt_number)
);
CREATE UNIQUE INDEX ux_attempts_provider_ref ON payment_attempts (provider_code, provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX ix_attempts_payment ON payment_attempts (payment_id);
CREATE INDEX ix_attempts_status_check ON payment_attempts (next_status_check_at) WHERE next_status_check_at IS NOT NULL;

CREATE TABLE refunds (
    id                    text PRIMARY KEY,
    payment_id            text        NOT NULL REFERENCES payments (id),
    attempt_id            text        NOT NULL REFERENCES payment_attempts (id),
    merchant_id           text        NOT NULL,
    provider_code         text        NOT NULL,
    amount                bigint      NOT NULL CHECK (amount > 0),
    currency              char(3)     NOT NULL,
    status                text        NOT NULL CHECK (status IN ('INITIATED', 'PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED')),
    reason                text,
    merchant_refund_id    text,
    initiated_by          text        NOT NULL CHECK (initiated_by IN ('MERCHANT', 'SYSTEM_LATE_SUCCESS', 'SYSTEM_DUPLICATE_SUCCESS')),
    provider_reference    text,
    failure_code          text,
    failure_message       text,
    next_status_check_at  timestamptz,
    status_check_count    integer     NOT NULL DEFAULT 0,
    needs_review          boolean     NOT NULL DEFAULT false,
    version               bigint      NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);
CREATE UNIQUE INDEX ux_refunds_merchant_ref ON refunds (merchant_id, merchant_refund_id) WHERE merchant_refund_id IS NOT NULL;
CREATE UNIQUE INDEX ux_refunds_provider_ref ON refunds (provider_code, provider_reference) WHERE provider_reference IS NOT NULL;
CREATE INDEX ix_refunds_payment ON refunds (payment_id);
CREATE INDEX ix_refunds_status_check ON refunds (next_status_check_at) WHERE next_status_check_at IS NOT NULL;

CREATE TABLE payment_transitions (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id   text        NOT NULL,
    merchant_id  text        NOT NULL,
    entity       text        NOT NULL CHECK (entity IN ('PAYMENT', 'ATTEMPT', 'REFUND')),
    entity_id    text        NOT NULL,
    from_status  text,
    to_status    text        NOT NULL,
    source       text        NOT NULL,
    reason       text,
    occurred_at  timestamptz NOT NULL
);
CREATE INDEX ix_transitions_payment ON payment_transitions (payment_id, id);

CREATE TABLE idempotency_records (
    merchant_id      text        NOT NULL,
    idempotency_key  text        NOT NULL,
    request_hash     bytea       NOT NULL,
    status           text        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  integer,
    response_body    text,
    locked_until     timestamptz,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    PRIMARY KEY (merchant_id, idempotency_key)
);
CREATE INDEX ix_idempotency_expires ON idempotency_records (expires_at);

CREATE TABLE provider_webhook_events (
    id                 text PRIMARY KEY,
    provider_code      text        NOT NULL,
    provider_event_id  text        NOT NULL,
    event_type         text        NOT NULL,
    payload            text        NOT NULL,
    normalized_event   jsonb       NOT NULL,
    status             text        NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    attempts           integer     NOT NULL DEFAULT 0,
    last_error         text,
    next_attempt_at    timestamptz,
    received_at        timestamptz NOT NULL,
    processed_at       timestamptz,
    CONSTRAINT ux_provider_event UNIQUE (provider_code, provider_event_id)
);
CREATE INDEX ix_provider_webhook_retry ON provider_webhook_events (next_attempt_at) WHERE status = 'RECEIVED';

CREATE TABLE merchant_events (
    id           text PRIMARY KEY,
    merchant_id  text        NOT NULL REFERENCES merchants (id),
    type         text        NOT NULL,
    resource_id  text        NOT NULL,
    payload      text        NOT NULL,
    created_at   timestamptz NOT NULL
);
CREATE INDEX ix_merchant_events_merchant ON merchant_events (merchant_id, created_at DESC);
CREATE INDEX ix_merchant_events_resource ON merchant_events (resource_id);

CREATE TABLE webhook_deliveries (
    id                    text PRIMARY KEY,
    event_id              text        NOT NULL REFERENCES merchant_events (id),
    merchant_id           text        NOT NULL,
    url                   text        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD')),
    attempt_count         integer     NOT NULL DEFAULT 0,
    next_attempt_at       timestamptz,
    last_response_status  integer,
    last_error            text,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);
CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_webhook_deliveries_event ON webhook_deliveries (event_id);

CREATE TABLE routing_rules (
    id              text PRIMARY KEY,
    merchant_id     text REFERENCES merchants (id),
    name            text        NOT NULL,
    priority        integer     NOT NULL,
    enabled         boolean     NOT NULL DEFAULT true,
    conditions      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    strategy        text        NOT NULL CHECK (strategy IN ('PRIORITY', 'WEIGHTED', 'DYNAMIC')),
    targets         jsonb       NOT NULL,
    allow_fallback  boolean     NOT NULL DEFAULT true,
    version         bigint      NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);
CREATE INDEX ix_routing_rules_scope ON routing_rules (merchant_id, priority) WHERE enabled;

CREATE TABLE audit_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_type     text        NOT NULL,
    actor_id       text        NOT NULL,
    action         text        NOT NULL,
    resource_type  text        NOT NULL,
    resource_id    text        NOT NULL,
    details        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    request_id     text,
    occurred_at    timestamptz NOT NULL
);
CREATE INDEX ix_audit_log_resource ON audit_log (resource_type, resource_id);

CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$$;

CREATE TRIGGER trg_payment_transitions_append_only
    BEFORE UPDATE OR DELETE ON payment_transitions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
