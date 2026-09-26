-- Manual ledger adjustments with maker-checker (ADR-024).

CREATE TABLE ledger_adjustments (
    id              text PRIMARY KEY,
    merchant_id     text        NOT NULL REFERENCES merchants (id),
    provider_code   text        NOT NULL,
    debit_account   text        NOT NULL,
    credit_account  text        NOT NULL,
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL,
    reason          text        NOT NULL,
    reference       text,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by    text        NOT NULL,
    requested_at    timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    decided_by      text,
    decided_at      timestamptz,
    decision_note   text,
    CONSTRAINT ck_adjustment_accounts_differ CHECK (debit_account <> credit_account),
    -- The database, not only the application, refuses a self-approved adjustment.
    CONSTRAINT ck_adjustment_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> requested_by)
);
CREATE INDEX ix_ledger_adjustments_status ON ledger_adjustments (status, requested_at);

ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_type_check
    CHECK (type IN ('PAYMENT_CAPTURED', 'REFUND_SUCCEEDED', 'PSP_FEE', 'SETTLEMENT', 'CHARGEBACK', 'REVERSAL',
                    'ADJUSTMENT'));
