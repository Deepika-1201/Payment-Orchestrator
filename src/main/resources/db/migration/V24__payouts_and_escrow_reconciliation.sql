CREATE TABLE payout_instructions (
    id text PRIMARY KEY CHECK (length(id) BETWEEN 4 AND 36),
    merchant_id text NOT NULL REFERENCES merchants (id),
    batch_id text NOT NULL UNIQUE,
    kyc_id text NOT NULL,
    provider_code text NOT NULL,
    amount bigint NOT NULL CHECK (amount > 0),
    instruction_enc bytea NOT NULL,
    instruction_hash bytea NOT NULL CHECK (octet_length(instruction_hash) = 32),
    status text NOT NULL CHECK (status IN ('QUEUED', 'PENDING', 'REVIEW', 'SUCCEEDED', 'RETURNED', 'REJECTED', 'CANCELLED')),
    dispatched_at timestamptz,
    provider_reference text,
    bank_reference text,
    reason text,
    checks integer NOT NULL DEFAULT 0 CHECK (checks >= 0),
    next_check_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (batch_id, merchant_id) REFERENCES settlement_batches (id, merchant_id),
    FOREIGN KEY (kyc_id, merchant_id) REFERENCES merchant_kyc_submissions (id, merchant_id),
    UNIQUE (provider_code, provider_reference),
    CHECK (CASE WHEN status IN ('QUEUED', 'CANCELLED') THEN dispatched_at IS NULL ELSE dispatched_at IS NOT NULL END),
    CHECK (dispatched_at IS NULL OR dispatched_at >= created_at)
);
CREATE INDEX ix_payout_due ON payout_instructions (next_check_at, id) WHERE next_check_at IS NOT NULL;
CREATE FUNCTION payout_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payout instructions are retained';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'QUEUED' OR NEW.dispatched_at IS NOT NULL
           OR NOT EXISTS (SELECT 1 FROM settlement_batches WHERE id = NEW.batch_id AND merchant_id = NEW.merchant_id
                           AND kyc_id = NEW.kyc_id AND amount = NEW.amount AND status = 'APPROVED') THEN
            RAISE EXCEPTION 'a payout requires an approved batch with the same merchant, amount and KYC';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id, NEW.merchant_id, NEW.batch_id, NEW.kyc_id, NEW.provider_code, NEW.amount, NEW.instruction_hash, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.merchant_id, OLD.batch_id, OLD.kyc_id, OLD.provider_code, OLD.amount, OLD.instruction_hash, OLD.created_at)
       OR (OLD.dispatched_at IS NOT NULL AND NEW.dispatched_at IS DISTINCT FROM OLD.dispatched_at)
       OR (OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference)
       OR NEW.checks < OLD.checks THEN
        RAISE EXCEPTION 'payout instruction, dispatch marker and provider identity are immutable';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('PENDING', 'CANCELLED'))
        OR (OLD.status IN ('PENDING', 'REVIEW') AND NEW.status IN ('PENDING', 'REVIEW', 'SUCCEEDED', 'REJECTED', 'RETURNED'))
        OR (OLD.status = 'SUCCEEDED' AND NEW.status = 'RETURNED')
    ) THEN
        RAISE EXCEPTION 'invalid payout transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payout_guard BEFORE INSERT OR UPDATE OR DELETE ON payout_instructions
    FOR EACH ROW EXECUTE FUNCTION payout_guard();

CREATE TABLE payout_transitions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id text NOT NULL REFERENCES payout_instructions (id),
    from_status text,
    to_status text NOT NULL,
    reason text,
    occurred_at timestamptz NOT NULL
);
CREATE TRIGGER trg_payout_transitions_immutable BEFORE UPDATE OR DELETE ON payout_transitions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE payout_webhook_inbox (
    id text PRIMARY KEY,
    provider_code text NOT NULL,
    provider_event_id text NOT NULL,
    payout_id text NOT NULL,
    event_enc bytea NOT NULL,
    event_hash bytea NOT NULL CHECK (octet_length(event_hash) = 32),
    status text NOT NULL CHECK (status IN ('PENDING', 'DONE')),
    attempts integer NOT NULL DEFAULT 0,
    next_check_at timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (provider_code, provider_event_id)
);
CREATE INDEX ix_payout_inbox_due ON payout_webhook_inbox (next_check_at) WHERE status = 'PENDING';
CREATE FUNCTION payout_inbox_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.provider_code, NEW.provider_event_id, NEW.payout_id, NEW.event_hash, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.provider_code, OLD.provider_event_id, OLD.payout_id, OLD.event_hash, OLD.created_at)
       OR (OLD.status = 'DONE' AND NEW.status <> 'DONE') THEN
        RAISE EXCEPTION 'payout inbox evidence and completion are immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_payout_inbox_guard BEFORE UPDATE ON payout_webhook_inbox
    FOR EACH ROW EXECUTE FUNCTION payout_inbox_guard();

CREATE TABLE treasury_exceptions (
    id text PRIMARY KEY,
    merchant_id text REFERENCES merchants (id),
    type text NOT NULL,
    reference text NOT NULL,
    details text NOT NULL,
    status text NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by text,
    resolution text,
    CHECK ((status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL AND resolution IS NOT NULL))
);
CREATE UNIQUE INDEX ux_treasury_exception_open ON treasury_exceptions (type, reference) WHERE status = 'OPEN';

CREATE TABLE simulated_payouts (
    id text PRIMARY KEY,
    provider_reference text NOT NULL UNIQUE,
    instruction_hash bytea NOT NULL,
    instruction_enc bytea NOT NULL,
    outcome text NOT NULL,
    create_calls integer NOT NULL DEFAULT 1,
    fetch_calls integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);

CREATE FUNCTION payout_ledger_proof() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('SUCCEEDED', 'RETURNED') THEN
        PERFORM treasury_assert_pair(NEW.merchant_id, 'PAYOUT', NEW.id, 'PAYOUT_SENT', 'SETTLEMENT_PAYABLE', 'ESCROW', NEW.amount);
    END IF;
    IF NEW.status = 'RETURNED' THEN
        PERFORM treasury_assert_pair(NEW.merchant_id, 'PAYOUT', NEW.id, 'PAYOUT_RETURNED', 'ESCROW', 'MERCHANT_PAYABLE', NEW.amount);
    END IF;
    IF NEW.status IN ('REJECTED', 'CANCELLED') THEN
        PERFORM treasury_assert_pair(NEW.merchant_id, 'PAYOUT', NEW.id, 'SETTLEMENT_RELEASED', 'SETTLEMENT_PAYABLE', 'MERCHANT_PAYABLE', NEW.amount);
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_payout_ledger_proof AFTER INSERT OR UPDATE ON payout_instructions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payout_ledger_proof();

CREATE TABLE escrow_bank_statements (
    id text PRIMARY KEY,
    reference text NOT NULL UNIQUE,
    account_reference text NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    opening_balance bigint NOT NULL CHECK (opening_balance >= 0),
    closing_balance bigint NOT NULL CHECK (closing_balance >= 0),
    imported_at timestamptz NOT NULL,
    imported_by text NOT NULL,
    created_in xid8 NOT NULL DEFAULT pg_current_xact_id(),
    CHECK (window_end > window_start AND window_end - window_start <= interval '31 days')
);
CREATE TABLE escrow_bank_entries (
    statement_id text NOT NULL REFERENCES escrow_bank_statements (id),
    bank_entry_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('PSP_RECEIPT', 'PAYOUT_DEBIT', 'PAYOUT_RETURN', 'ADJUSTMENT', 'UNMATCHED')),
    reference_id text NOT NULL,
    amount bigint NOT NULL CHECK (amount <> 0),
    booked_at timestamptz NOT NULL,
    PRIMARY KEY (statement_id, bank_entry_id),
    CHECK ((kind <> 'PAYOUT_DEBIT' OR amount < 0) AND (kind <> 'PAYOUT_RETURN' OR amount > 0))
);
CREATE TRIGGER trg_bank_statement_immutable BEFORE UPDATE OR DELETE ON escrow_bank_statements
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_bank_entries_immutable BEFORE UPDATE OR DELETE ON escrow_bank_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE FUNCTION bank_entry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    header escrow_bank_statements%ROWTYPE;
BEGIN
    SELECT * INTO header FROM escrow_bank_statements WHERE id = NEW.statement_id;
    IF header.created_in <> pg_current_xact_id() OR NEW.booked_at < header.window_start OR NEW.booked_at >= header.window_end THEN
        RAISE EXCEPTION 'bank entries belong to the statement creation transaction and window';
    END IF;
    IF EXISTS (SELECT 1 FROM escrow_bank_entries entry JOIN escrow_bank_statements statement ON statement.id = entry.statement_id
                WHERE statement.account_reference = header.account_reference AND entry.bank_entry_id = NEW.bank_entry_id
                  AND ROW(entry.kind, entry.reference_id, entry.amount, entry.booked_at)
                      IS DISTINCT FROM ROW(NEW.kind, NEW.reference_id, NEW.amount, NEW.booked_at)) THEN
        RAISE EXCEPTION 'bank entry id reused with different facts';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_bank_entry_guard BEFORE INSERT ON escrow_bank_entries FOR EACH ROW EXECUTE FUNCTION bank_entry_guard();
CREATE FUNCTION bank_statement_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.opening_balance + coalesce((SELECT sum(amount) FROM escrow_bank_entries WHERE statement_id = NEW.id), 0) <> NEW.closing_balance THEN
        RAISE EXCEPTION 'bank statement balances must match its entries';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_bank_statement_balanced AFTER INSERT ON escrow_bank_statements
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bank_statement_balanced();

CREATE TABLE escrow_reconciliation_results (
    id text PRIMARY KEY,
    statement_id text NOT NULL REFERENCES escrow_bank_statements (id),
    ledger_balance bigint NOT NULL,
    bank_balance bigint NOT NULL,
    transit_amount bigint NOT NULL,
    difference bigint NOT NULL,
    matched boolean NOT NULL,
    issues jsonb NOT NULL,
    in_transit jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (difference = bank_balance + transit_amount - ledger_balance)
);
CREATE TRIGGER trg_escrow_reconciliation_immutable BEFORE UPDATE OR DELETE ON escrow_reconciliation_results
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

ALTER TABLE treasury_control ADD COLUMN bank_cutoff timestamptz,
    ADD COLUMN bank_mismatch boolean NOT NULL DEFAULT false;

CREATE TABLE escrow_cash_holds (
    merchant_id text PRIMARY KEY REFERENCES merchants (id),
    amount bigint NOT NULL CHECK (amount > 0)
);

ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check
    CHECK (type IN ('PSP_RECEIVABLE', 'SALES_CLEARING', 'PSP_FEES', 'REFUNDS', 'CHARGEBACKS', 'BANK_SETTLEMENTS',
                    'CUSTOMER_FUNDS', 'FX_CONVERSION', 'FX_GAIN_LOSS', 'ESCROW', 'MERCHANT_PAYABLE', 'RESERVE',
                    'SETTLEMENT_PAYABLE', 'PLATFORM_FEE_INCOME', 'GST_PAYABLE', 'PLATFORM_FUNDING'));

CREATE TABLE escrow_adjustments (
    id text PRIMARY KEY,
    reference text NOT NULL UNIQUE,
    owner_id text NOT NULL REFERENCES merchants (id),
    kind text NOT NULL CHECK (kind IN ('BANK_FEE', 'FEE_REVERSAL', 'PREFUNDING')),
    amount bigint NOT NULL CHECK (amount > 0),
    statement_id text NOT NULL,
    bank_entry_id text NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    status text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL,
    decided_by text,
    decided_at timestamptz,
    FOREIGN KEY (statement_id, bank_entry_id) REFERENCES escrow_bank_entries (statement_id, bank_entry_id),
    UNIQUE (statement_id, bank_entry_id),
    UNIQUE (bank_entry_id),
    CHECK (CASE WHEN status = 'PENDING' THEN decided_by IS NULL AND decided_at IS NULL
           ELSE decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_at >= created_at END),
    CONSTRAINT ck_escrow_adjustment_checker CHECK (status <> 'APPROVED' OR decided_by <> created_by)
);
CREATE FUNCTION escrow_adjustment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'escrow corrections are retained';
    END IF;
    IF ROW(NEW.id, NEW.reference, NEW.owner_id, NEW.kind, NEW.amount, NEW.statement_id, NEW.bank_entry_id,
           NEW.reason, NEW.created_by, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.reference, OLD.owner_id, OLD.kind, OLD.amount, OLD.statement_id, OLD.bank_entry_id,
           OLD.reason, OLD.created_by, OLD.created_at) OR (OLD.status <> 'PENDING' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'escrow correction facts and completed decisions are immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_escrow_adjustment_guard BEFORE UPDATE OR DELETE ON escrow_adjustments
    FOR EACH ROW EXECUTE FUNCTION escrow_adjustment_guard();
CREATE FUNCTION escrow_adjustment_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    movement bigint;
BEGIN
    IF NEW.status = 'APPROVED' THEN
        SELECT amount INTO movement FROM escrow_bank_entries WHERE statement_id = NEW.statement_id AND bank_entry_id = NEW.bank_entry_id;
        IF movement IS DISTINCT FROM (CASE NEW.kind WHEN 'BANK_FEE' THEN -NEW.amount ELSE NEW.amount END) THEN
            RAISE EXCEPTION 'escrow correction must match its bank evidence';
        END IF;
        PERFORM treasury_assert_pair(NEW.owner_id, 'ESCROW_ADJUSTMENT', NEW.id, 'ADJUSTMENT',
            CASE NEW.kind WHEN 'BANK_FEE' THEN 'PLATFORM_FEE_INCOME' ELSE 'ESCROW' END,
            CASE NEW.kind WHEN 'PREFUNDING' THEN 'PLATFORM_FUNDING' WHEN 'BANK_FEE' THEN 'ESCROW' ELSE 'PLATFORM_FEE_INCOME' END,
            NEW.amount);
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_escrow_adjustment_ledger AFTER INSERT OR UPDATE ON escrow_adjustments
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION escrow_adjustment_ledger();