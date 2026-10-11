ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check
    CHECK (type IN ('PSP_RECEIVABLE', 'SALES_CLEARING', 'PSP_FEES', 'REFUNDS', 'CHARGEBACKS', 'BANK_SETTLEMENTS',
                    'CUSTOMER_FUNDS', 'FX_CONVERSION', 'FX_GAIN_LOSS', 'ESCROW', 'MERCHANT_PAYABLE', 'RESERVE',
                    'SETTLEMENT_PAYABLE', 'PLATFORM_FEE_INCOME', 'GST_PAYABLE'));
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_type_check
    CHECK (type IN ('PAYMENT_CAPTURED', 'REFUND_SUCCEEDED', 'PSP_FEE', 'SETTLEMENT', 'CHARGEBACK', 'REVERSAL',
                    'ADJUSTMENT', 'CREDIT_RECEIVED', 'CREDIT_APPLIED', 'CREDIT_RETURNED', 'FX_CONVERSION',
                    'BILLING_INVOICE', 'BILLING_CREDIT', 'RESERVE_HELD', 'RESERVE_RELEASED', 'SETTLEMENT_RESERVED',
                    'SETTLEMENT_RELEASED', 'PAYOUT_SENT', 'PAYOUT_RETURNED'));
CREATE TRIGGER trg_ledger_account_identity BEFORE UPDATE OR DELETE ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE treasury_control (
    id integer PRIMARY KEY CHECK (id = 1),
    version bigint NOT NULL CHECK (version >= 0)
);
INSERT INTO treasury_control VALUES (1, 0);

CREATE TABLE treasury_movements (
    kind text NOT NULL CHECK (kind IN ('CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL',
                                      'CREDIT_RECEIVED', 'CREDIT_APPLIED', 'CREDIT_RETURNED')),
    reference_id text NOT NULL,
    merchant_id text NOT NULL REFERENCES merchants (id),
    provider_code text NOT NULL,
    payment_id text,
    amount bigint NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    merchant_entitled boolean NOT NULL,
    occurred_at timestamptz NOT NULL,
    eligible_at timestamptz NOT NULL,
    PRIMARY KEY (kind, reference_id)
);
CREATE TRIGGER trg_treasury_movement_immutable BEFORE UPDATE OR DELETE ON treasury_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE treasury_conversions (
    id text PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL')),
    reference_id text NOT NULL,
    merchant_id text NOT NULL REFERENCES merchants (id),
    provider_code text NOT NULL,
    amount bigint NOT NULL CHECK (amount > 0),
    occurred_at timestamptz NOT NULL,
    UNIQUE (kind, reference_id)
);
CREATE TRIGGER trg_treasury_conversion_immutable BEFORE UPDATE OR DELETE ON treasury_conversions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE settlement_policies (
    id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES merchants (id),
    kyc_id text NOT NULL,
    business_days integer NOT NULL CHECK (business_days BETWEEN 1 AND 30),
    reserve_target bigint NOT NULL CHECK (reserve_target BETWEEN 0 AND 1000000000000),
    held boolean NOT NULL,
    bank_registration_reference text NOT NULL CHECK (length(bank_registration_reference) BETWEEN 1 AND 200),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (kyc_id, merchant_id) REFERENCES merchant_kyc_submissions (id, merchant_id),
    UNIQUE (id, merchant_id)
);
CREATE INDEX ix_settlement_policy_latest ON settlement_policies (merchant_id, created_at DESC, id DESC);
CREATE TRIGGER trg_settlement_policy_immutable BEFORE UPDATE OR DELETE ON settlement_policies
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE treasury_sources (
    id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES merchants (id),
    provider_code text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('CAPTURE', 'CREDIT_APPLIED', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL',
                                      'PSP_FEE', 'INVOICE', 'CREDIT_NOTE', 'RESERVE_HELD', 'RESERVE_RELEASED', 'PAYOUT_RELEASED')),
    reference_id text NOT NULL,
    amount bigint NOT NULL CHECK (amount > 0),
    tax bigint NOT NULL DEFAULT 0 CHECK (tax >= 0 AND tax <= amount),
    payable_delta bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    eligible_at timestamptz NOT NULL,
    CHECK (tax = 0 OR kind IN ('INVOICE', 'CREDIT_NOTE')),
    CHECK (payable_delta = CASE WHEN kind IN ('CAPTURE', 'CREDIT_APPLIED', 'CHARGEBACK_REVERSAL', 'CREDIT_NOTE',
                                              'RESERVE_RELEASED', 'PAYOUT_RELEASED') THEN amount ELSE -amount END),
    UNIQUE (merchant_id, kind, reference_id),
    UNIQUE (id, merchant_id)
);
CREATE INDEX ix_treasury_source_eligible ON treasury_sources (merchant_id, eligible_at, id);
CREATE TRIGGER trg_treasury_source_immutable BEFORE UPDATE OR DELETE ON treasury_sources
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE treasury_psp_fees (
    reference_id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES merchants (id),
    provider_code text NOT NULL,
    amount bigint NOT NULL CHECK (amount > 0),
    merchant_amount bigint NOT NULL CHECK (merchant_amount BETWEEN 0 AND amount),
    occurred_at timestamptz NOT NULL
);
CREATE TRIGGER trg_treasury_fee_immutable BEFORE UPDATE OR DELETE ON treasury_psp_fees
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE escrow_receipts (
    id text PRIMARY KEY,
    owner_id text NOT NULL REFERENCES merchants (id),
    provider_code text NOT NULL,
    settlement_id text NOT NULL,
    amount bigint NOT NULL,
    bank_reference text,
    settled_at timestamptz NOT NULL,
    created_in xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (owner_id, provider_code, settlement_id)
);
CREATE TABLE escrow_receipt_allocations (
    receipt_id text NOT NULL REFERENCES escrow_receipts (id),
    merchant_id text NOT NULL REFERENCES merchants (id),
    amount bigint NOT NULL,
    PRIMARY KEY (receipt_id, merchant_id)
);
CREATE TRIGGER trg_escrow_receipt_immutable BEFORE UPDATE OR DELETE ON escrow_receipts
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_escrow_allocation_immutable BEFORE UPDATE OR DELETE ON escrow_receipt_allocations
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE FUNCTION escrow_allocation_in_creation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM escrow_receipts WHERE id = NEW.receipt_id AND created_in = pg_current_xact_id()) THEN
        RAISE EXCEPTION 'escrow allocation must be inserted with its receipt';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_escrow_allocation_creation BEFORE INSERT ON escrow_receipt_allocations
    FOR EACH ROW EXECUTE FUNCTION escrow_allocation_in_creation();
CREATE FUNCTION escrow_receipt_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    allocation record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM escrow_receipt_allocations WHERE receipt_id = NEW.id)
       OR NEW.amount <> (SELECT sum(amount) FROM escrow_receipt_allocations WHERE receipt_id = NEW.id) THEN
        RAISE EXCEPTION 'escrow receipt allocations must match the complete payout';
    END IF;
    FOR allocation IN SELECT * FROM escrow_receipt_allocations WHERE receipt_id = NEW.id LOOP
        IF allocation.amount <> 0 THEN
            PERFORM treasury_assert_pair(allocation.merchant_id, 'ESCROW_RECEIPT', NEW.id || ':' || allocation.merchant_id,
                'SETTLEMENT', CASE WHEN allocation.amount > 0 THEN 'ESCROW' ELSE 'PSP_RECEIVABLE' END,
                CASE WHEN allocation.amount > 0 THEN 'PSP_RECEIVABLE' ELSE 'ESCROW' END, abs(allocation.amount));
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_escrow_receipt_balanced AFTER INSERT ON escrow_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION escrow_receipt_balanced();

CREATE TABLE settlement_batches (
    id text PRIMARY KEY,
    merchant_id text NOT NULL REFERENCES merchants (id),
    reference text NOT NULL CHECK (length(reference) BETWEEN 1 AND 128),
    policy_id text NOT NULL,
    kyc_id text NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    captures bigint NOT NULL,
    refunds bigint NOT NULL,
    chargebacks bigint NOT NULL,
    psp_fees bigint NOT NULL,
    service_fees bigint NOT NULL,
    gst bigint NOT NULL,
    reserve_change bigint NOT NULL,
    released_payouts bigint NOT NULL,
    amount bigint NOT NULL CHECK (amount >= 0),
    created_by text NOT NULL,
    created_at timestamptz NOT NULL,
    decided_by text,
    decided_at timestamptz,
    decision_reason text,
    created_in xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (merchant_id, reference),
    UNIQUE (id, merchant_id),
    FOREIGN KEY (policy_id, merchant_id) REFERENCES settlement_policies (id, merchant_id),
    FOREIGN KEY (kyc_id, merchant_id) REFERENCES merchant_kyc_submissions (id, merchant_id),
    CHECK (amount = captures - refunds - chargebacks - psp_fees - service_fees - gst - reserve_change + released_payouts),
    CHECK (CASE status WHEN 'DRAFT' THEN decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL
        ELSE decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_at >= created_at
             AND decision_reason IS NOT NULL AND length(decision_reason) BETWEEN 1 AND 1000 END),
    CONSTRAINT ck_settlement_checker CHECK (status <> 'APPROVED' OR decided_by <> created_by)
);
CREATE UNIQUE INDEX ux_settlement_draft ON settlement_batches (merchant_id) WHERE status = 'DRAFT';
CREATE INDEX ix_settlement_batch_history ON settlement_batches (merchant_id, created_at DESC, id DESC);

CREATE TABLE settlement_batch_items (
    batch_id text NOT NULL,
    source_id text NOT NULL,
    merchant_id text NOT NULL,
    PRIMARY KEY (batch_id, source_id),
    FOREIGN KEY (batch_id, merchant_id) REFERENCES settlement_batches (id, merchant_id),
    FOREIGN KEY (source_id, merchant_id) REFERENCES treasury_sources (id, merchant_id)
);
CREATE TABLE settlement_source_claims (
    source_id text PRIMARY KEY,
    batch_id text NOT NULL,
    FOREIGN KEY (batch_id, source_id) REFERENCES settlement_batch_items (batch_id, source_id)
);
CREATE FUNCTION settlement_batch_review_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'settlement batches are retained';
    END IF;
    IF ROW(NEW.id, NEW.merchant_id, NEW.reference, NEW.policy_id, NEW.kyc_id, NEW.captures, NEW.refunds,
           NEW.chargebacks, NEW.psp_fees, NEW.service_fees, NEW.gst, NEW.reserve_change, NEW.released_payouts,
           NEW.amount, NEW.created_by, NEW.created_at, NEW.created_in)
       IS DISTINCT FROM ROW(OLD.id, OLD.merchant_id, OLD.reference, OLD.policy_id, OLD.kyc_id, OLD.captures, OLD.refunds,
           OLD.chargebacks, OLD.psp_fees, OLD.service_fees, OLD.gst, OLD.reserve_change, OLD.released_payouts,
           OLD.amount, OLD.created_by, OLD.created_at, OLD.created_in)
       OR (OLD.status <> 'DRAFT' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'settlement batch facts and completed approval are immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_settlement_batch_immutable BEFORE UPDATE OR DELETE ON settlement_batches
    FOR EACH ROW EXECUTE FUNCTION settlement_batch_review_once();
CREATE TRIGGER trg_settlement_items_immutable BEFORE UPDATE OR DELETE ON settlement_batch_items
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE FUNCTION settlement_item_in_creation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM settlement_batches
                    WHERE id = NEW.batch_id AND created_in = pg_current_xact_id() AND status = 'DRAFT') THEN
        RAISE EXCEPTION 'settlement items must be inserted with the draft';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_settlement_item_creation BEFORE INSERT ON settlement_batch_items
    FOR EACH ROW EXECUTE FUNCTION settlement_item_in_creation();
CREATE FUNCTION settlement_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF NOT EXISTS (SELECT 1 FROM settlement_batches WHERE id = OLD.batch_id AND status = 'REJECTED') THEN
            RAISE EXCEPTION 'only rejected drafts release settlement source claims';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' OR NOT EXISTS (SELECT 1 FROM settlement_batches
                    WHERE id = NEW.batch_id AND created_in = pg_current_xact_id() AND status = 'DRAFT') THEN
        RAISE EXCEPTION 'source claims are created only with a new settlement draft';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_settlement_claim_guard BEFORE INSERT OR UPDATE OR DELETE ON settlement_source_claims
    FOR EACH ROW EXECUTE FUNCTION settlement_claim_guard();
CREATE FUNCTION settlement_batch_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actual record;
    missing_claim boolean;
    current_status text;
BEGIN
    SELECT coalesce(sum(amount) FILTER (WHERE kind IN ('CAPTURE', 'CREDIT_APPLIED')), 0) AS captures,
           coalesce(sum(amount) FILTER (WHERE kind = 'REFUND'), 0) AS refunds,
           coalesce(sum(CASE kind WHEN 'CHARGEBACK' THEN amount WHEN 'CHARGEBACK_REVERSAL' THEN -amount ELSE 0 END), 0) AS chargebacks,
           coalesce(sum(amount) FILTER (WHERE kind = 'PSP_FEE'), 0) AS psp_fees,
           coalesce(sum(CASE kind WHEN 'INVOICE' THEN amount-tax WHEN 'CREDIT_NOTE' THEN tax-amount ELSE 0 END), 0) AS service_fees,
           coalesce(sum(CASE kind WHEN 'INVOICE' THEN tax WHEN 'CREDIT_NOTE' THEN -tax ELSE 0 END), 0) AS gst,
           coalesce(sum(CASE kind WHEN 'RESERVE_HELD' THEN amount WHEN 'RESERVE_RELEASED' THEN -amount ELSE 0 END), 0) AS reserve_change,
           coalesce(sum(amount) FILTER (WHERE kind = 'PAYOUT_RELEASED'), 0) AS released_payouts,
           count(*) AS item_count, coalesce(bool_and(source.eligible_at <= NEW.created_at), false) AS eligible
      INTO actual FROM settlement_batch_items item JOIN treasury_sources source ON source.id = item.source_id
     WHERE item.batch_id = NEW.id;
    SELECT EXISTS (SELECT 1 FROM settlement_batch_items item WHERE item.batch_id = NEW.id
                    AND NOT EXISTS (SELECT 1 FROM settlement_source_claims claim
                                     WHERE claim.source_id = item.source_id AND claim.batch_id = NEW.id)) INTO missing_claim;
    SELECT status INTO current_status FROM settlement_batches WHERE id = NEW.id;
    IF actual.item_count = 0 OR NOT actual.eligible OR (current_status <> 'REJECTED' AND missing_claim)
       OR ROW(actual.captures, actual.refunds, actual.chargebacks, actual.psp_fees, actual.service_fees,
              actual.gst, actual.reserve_change, actual.released_payouts)
          IS DISTINCT FROM ROW(NEW.captures, NEW.refunds, NEW.chargebacks, NEW.psp_fees, NEW.service_fees,
                               NEW.gst, NEW.reserve_change, NEW.released_payouts) THEN
        RAISE EXCEPTION 'settlement batch totals, maturity and claims must match its immutable sources';
    END IF;
    IF NEW.amount > 0 THEN
        PERFORM treasury_assert_pair(NEW.merchant_id, 'SETTLEMENT_BATCH', NEW.id, 'SETTLEMENT_RESERVED',
            'MERCHANT_PAYABLE', 'SETTLEMENT_PAYABLE', NEW.amount);
        IF current_status = 'REJECTED' THEN
            PERFORM treasury_assert_pair(NEW.merchant_id, 'SETTLEMENT_BATCH', NEW.id, 'SETTLEMENT_RELEASED',
                'SETTLEMENT_PAYABLE', 'MERCHANT_PAYABLE', NEW.amount);
        END IF;
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_settlement_batch_balanced AFTER INSERT OR UPDATE ON settlement_batches
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION settlement_batch_balanced();

CREATE FUNCTION treasury_assert_pair(merchant text, reference_kind text, reference text, transaction_kind text,
                                     debit_account text, credit_account text, expected bigint)
    RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    entries integer;
    correct integer;
BEGIN
    SELECT count(*), count(*) FILTER (WHERE account.merchant_id = merchant AND account.provider_code = 'AGGREGATOR'
           AND entry.currency = 'INR' AND entry.amount = expected
           AND ((account.type = debit_account AND entry.direction = 'DEBIT')
             OR (account.type = credit_account AND entry.direction = 'CREDIT')))
      INTO entries, correct FROM ledger_transactions posting
      JOIN ledger_entries entry ON entry.transaction_id = posting.id
      JOIN ledger_accounts account ON account.id = entry.account_id
     WHERE posting.reference_type = reference_kind AND posting.reference_id = reference
       AND posting.type = transaction_kind AND posting.merchant_id = merchant AND posting.provider_code = 'AGGREGATOR';
    IF entries <> 2 OR correct <> 2 THEN
        RAISE EXCEPTION 'missing or mismatched authoritative ledger posting for %', reference;
    END IF;
END
$$;