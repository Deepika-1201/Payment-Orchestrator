-- Phase 13: shadow double-entry ledger (ADR-012) and PSP reconciliation (FR-RC1..4).

CREATE TABLE ledger_accounts (
    id             text PRIMARY KEY,
    merchant_id    text        NOT NULL REFERENCES merchants (id),
    provider_code  text        NOT NULL,
    type           text        NOT NULL CHECK (type IN ('PSP_RECEIVABLE', 'SALES_CLEARING', 'PSP_FEES', 'REFUNDS',
                                                        'CHARGEBACKS', 'BANK_SETTLEMENTS')),
    currency       char(3)     NOT NULL,
    normal_side    text        NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    created_at     timestamptz NOT NULL,
    CONSTRAINT ux_ledger_account UNIQUE (merchant_id, provider_code, type, currency)
);

CREATE TABLE ledger_transactions (
    id              text PRIMARY KEY,
    merchant_id     text        NOT NULL,
    provider_code   text        NOT NULL,
    type            text        NOT NULL CHECK (type IN ('PAYMENT_CAPTURED', 'REFUND_SUCCEEDED', 'PSP_FEE', 'SETTLEMENT',
                                                         'CHARGEBACK', 'REVERSAL')),
    reference_type  text        NOT NULL,
    reference_id    text        NOT NULL,
    description     text,
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT ux_ledger_transaction_reference UNIQUE (reference_type, reference_id, type)
);
CREATE INDEX ix_ledger_transactions_scope ON ledger_transactions (merchant_id, provider_code, occurred_at);
CREATE INDEX ix_ledger_transactions_reference ON ledger_transactions (reference_id);

CREATE TABLE ledger_entries (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  text        NOT NULL REFERENCES ledger_transactions (id),
    account_id      text        NOT NULL REFERENCES ledger_accounts (id),
    direction       text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX ix_ledger_entries_account ON ledger_entries (account_id);
CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);

-- Checked at commit: every ledger transaction has at least two entries and its debits equal its credits.
CREATE FUNCTION ledger_assert_balanced(tx_id text) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    entry_count integer;
    imbalance   bigint;
BEGIN
    SELECT count(*), COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO entry_count, imbalance
      FROM ledger_entries
     WHERE transaction_id = tx_id;
    IF entry_count < 2 OR imbalance <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced (entries=%, imbalance=%)', tx_id, entry_count, imbalance;
    END IF;
END
$$;

CREATE FUNCTION ledger_entries_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM ledger_assert_balanced(NEW.transaction_id);
    RETURN NULL;
END
$$;

CREATE FUNCTION ledger_transactions_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM ledger_assert_balanced(NEW.id);
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transactions_balanced();

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE reconciliation_runs (
    id                 text PRIMARY KEY,
    merchant_id        text        NOT NULL REFERENCES merchants (id),
    provider_code      text        NOT NULL,
    window_start       timestamptz NOT NULL,
    window_end         timestamptz NOT NULL,
    status             text        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    lines_total        integer     NOT NULL DEFAULT 0,
    lines_matched      integer     NOT NULL DEFAULT 0,
    lines_auto_healed  integer     NOT NULL DEFAULT 0,
    exceptions_opened  integer     NOT NULL DEFAULT 0,
    gross_amount       bigint      NOT NULL DEFAULT 0,
    refund_amount      bigint      NOT NULL DEFAULT 0,
    fee_amount         bigint      NOT NULL DEFAULT 0,
    settled_amount     bigint      NOT NULL DEFAULT 0,
    error              text,
    started_at         timestamptz NOT NULL,
    completed_at       timestamptz,
    CONSTRAINT ck_reconciliation_window CHECK (window_end > window_start)
);
CREATE INDEX ix_reconciliation_runs_scope ON reconciliation_runs (merchant_id, provider_code, window_start DESC);

CREATE TABLE reconciliation_lines (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id              text        NOT NULL REFERENCES reconciliation_runs (id),
    provider_code       text        NOT NULL,
    provider_line_id    text        NOT NULL,
    line_type           text        NOT NULL CHECK (line_type IN ('PAYMENT', 'REFUND')),
    provider_reference  text,
    merchant_reference  text,
    amount              bigint      NOT NULL,
    fee                 bigint      NOT NULL DEFAULT 0,
    currency            char(3)     NOT NULL,
    settlement_id       text,
    occurred_at         timestamptz,
    result              text        NOT NULL CHECK (result IN ('MATCHED', 'AUTO_HEALED', 'EXCEPTION')),
    entity_id           text,
    CONSTRAINT ux_reconciliation_line UNIQUE (run_id, provider_line_id)
);
CREATE INDEX ix_reconciliation_lines_entity ON reconciliation_lines (entity_id) WHERE entity_id IS NOT NULL;

CREATE TABLE reconciliation_exceptions (
    id               text PRIMARY KEY,
    run_id           text        NOT NULL REFERENCES reconciliation_runs (id),
    merchant_id      text        NOT NULL,
    provider_code    text        NOT NULL,
    type             text        NOT NULL CHECK (type IN ('MISSING_INTERNALLY', 'MISSING_AT_PROVIDER', 'AMOUNT_MISMATCH',
                                                          'STATUS_MISMATCH', 'DUPLICATE', 'SETTLEMENT_MISMATCH')),
    reference        text        NOT NULL,
    entity_id        text,
    expected_amount  bigint,
    actual_amount    bigint,
    details          text,
    status           text        NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution       text,
    created_at       timestamptz NOT NULL,
    resolved_at      timestamptz
);
CREATE UNIQUE INDEX ux_reconciliation_exception_open
    ON reconciliation_exceptions (merchant_id, provider_code, type, reference) WHERE status = 'OPEN';
CREATE INDEX ix_reconciliation_exceptions_status ON reconciliation_exceptions (status, created_at);

CREATE INDEX ix_transitions_succeeded
    ON payment_transitions (merchant_id, occurred_at)
    WHERE to_status = 'SUCCEEDED' AND entity IN ('ATTEMPT', 'REFUND');
