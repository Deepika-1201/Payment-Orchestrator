-- Phase 23: international cards and multi-currency (ADR-040, LLD §23).

ALTER TABLE merchants ADD COLUMN international_cards boolean NOT NULL DEFAULT false;

-- What the PSP converted to INR for one money movement of a payment in another currency (LLD §23.3).
CREATE TABLE fx_conversions (
    id              text PRIMARY KEY,
    merchant_id     text        NOT NULL REFERENCES merchants (id),
    provider_code   text        NOT NULL,
    payment_id      text        NOT NULL REFERENCES payments (id),
    attempt_id      text        NOT NULL REFERENCES payment_attempts (id),
    kind            text        NOT NULL CHECK (kind IN ('CAPTURE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL')),
    reference_id    text        NOT NULL,
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL CHECK (currency <> 'INR'),
    settled_amount  bigint      NOT NULL CHECK (settled_amount > 0),
    carried_amount  bigint      NOT NULL CHECK (carried_amount >= 0),
    rate            numeric     CHECK (rate > 0 AND rate NOT IN ('NaN'::numeric, 'Infinity'::numeric)),
    source          text        NOT NULL CHECK (source IN ('PSP', 'SETTLEMENT_REPORT')),
    recorded_at     timestamptz NOT NULL,
    CONSTRAINT ux_fx_conversion UNIQUE (kind, reference_id),
    CONSTRAINT ck_fx_capture CHECK (kind <> 'CAPTURE' OR (reference_id = attempt_id AND carried_amount = settled_amount))
);
CREATE INDEX ix_fx_conversions_attempt ON fx_conversions (attempt_id);

CREATE TRIGGER trg_fx_conversions_append_only
    BEFORE UPDATE OR DELETE ON fx_conversions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Foreign sales and their INR value are held apart from the INR receivable (LLD §23.4).
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check
    CHECK (type IN ('PSP_RECEIVABLE', 'SALES_CLEARING', 'PSP_FEES', 'REFUNDS', 'CHARGEBACKS', 'BANK_SETTLEMENTS',
                    'CUSTOMER_FUNDS', 'FX_CONVERSION', 'FX_GAIN_LOSS'));
ALTER TABLE ledger_accounts ADD CONSTRAINT ux_ledger_account_currency UNIQUE (id, currency);
ALTER TABLE ledger_entries ADD CONSTRAINT fk_ledger_entry_account_currency
    FOREIGN KEY (account_id, currency) REFERENCES ledger_accounts (id, currency);
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_type_check
    CHECK (type IN ('PAYMENT_CAPTURED', 'REFUND_SUCCEEDED', 'PSP_FEE', 'SETTLEMENT', 'CHARGEBACK', 'REVERSAL',
                    'ADJUSTMENT', 'CREDIT_RECEIVED', 'CREDIT_APPLIED', 'CREDIT_RETURNED', 'FX_CONVERSION'));

-- Checked at commit: every ledger transaction has at least two entries and balances in each of its currencies.
CREATE OR REPLACE FUNCTION ledger_assert_balanced(tx_id text) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    entry_count integer;
    unbalanced  text;
BEGIN
    SELECT count(*) INTO entry_count FROM ledger_entries WHERE transaction_id = tx_id;
    SELECT string_agg(currency || ' ' || imbalance, ', ' ORDER BY currency) INTO unbalanced
      FROM (SELECT currency, SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS imbalance
              FROM ledger_entries
             WHERE transaction_id = tx_id
             GROUP BY currency) per_currency
     WHERE imbalance <> 0;
    IF entry_count < 2 OR unbalanced IS NOT NULL THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced (entries=%, imbalance=%)', tx_id, entry_count,
            COALESCE(unbalanced, 'none');
    END IF;
END
$$;

-- Report lines are in INR; a line for an item charged in another currency also gives that amount (LLD §23.5).
ALTER TABLE reconciliation_lines ADD COLUMN charged_amount bigint;
ALTER TABLE reconciliation_lines ADD COLUMN charged_currency char(3);
ALTER TABLE reconciliation_lines ADD CONSTRAINT ck_reconciliation_line_charged
    CHECK ((charged_amount IS NULL) = (charged_currency IS NULL));

ALTER TABLE reconciliation_exceptions DROP CONSTRAINT reconciliation_exceptions_type_check;
ALTER TABLE reconciliation_exceptions ADD CONSTRAINT reconciliation_exceptions_type_check
    CHECK (type IN ('MISSING_INTERNALLY', 'MISSING_AT_PROVIDER', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'DUPLICATE',
                    'SETTLEMENT_MISMATCH', 'UNMATCHED_ADJUSTMENT', 'CONVERSION_MISSING'));
