-- Disputes and chargebacks (FR-D1, ADR-018).

CREATE TABLE disputes (
    id                   text PRIMARY KEY,
    payment_id           text        NOT NULL REFERENCES payments (id),
    attempt_id           text        NOT NULL REFERENCES payment_attempts (id),
    merchant_id          text        NOT NULL,
    provider_code        text        NOT NULL,
    provider_dispute_id  text        NOT NULL,
    amount               bigint      NOT NULL CHECK (amount > 0),
    currency             char(3)     NOT NULL,
    reason               text,
    status               text        NOT NULL CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'WON', 'LOST')),
    respond_by           timestamptz,
    needs_review         boolean     NOT NULL DEFAULT false,
    review_reason        text,
    flagged_at           timestamptz,
    version              bigint      NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    -- Scoped by merchant so one merchant's PSP account cannot claim another merchant's dispute ids (ADR-014).
    CONSTRAINT ux_disputes_provider_id UNIQUE (provider_code, merchant_id, provider_dispute_id)
);
CREATE INDEX ix_disputes_payment ON disputes (payment_id, created_at);
CREATE INDEX ix_disputes_attempt ON disputes (attempt_id);
CREATE INDEX ix_disputes_open_review ON disputes (flagged_at) WHERE needs_review;

ALTER TABLE payment_transitions DROP CONSTRAINT payment_transitions_entity_check;
ALTER TABLE payment_transitions ADD CONSTRAINT payment_transitions_entity_check
    CHECK (entity IN ('PAYMENT', 'ATTEMPT', 'REFUND', 'DISPUTE'));

ALTER TABLE reconciliation_lines DROP CONSTRAINT reconciliation_lines_line_type_check;
ALTER TABLE reconciliation_lines ADD CONSTRAINT reconciliation_lines_line_type_check
    CHECK (line_type IN ('PAYMENT', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL'));

-- Net chargebacks withheld in a run's window (chargebacks minus reversals).
ALTER TABLE reconciliation_runs ADD COLUMN chargeback_amount bigint NOT NULL DEFAULT 0;
