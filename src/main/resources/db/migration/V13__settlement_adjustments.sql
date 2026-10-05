-- Settlement reports of real PSPs (ADR-032): adjustments that match nothing in the gateway.

ALTER TABLE reconciliation_lines DROP CONSTRAINT reconciliation_lines_line_type_check;
ALTER TABLE reconciliation_lines ADD CONSTRAINT reconciliation_lines_line_type_check
    CHECK (line_type IN ('PAYMENT', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'ADJUSTMENT_CREDIT', 'ADJUSTMENT_DEBIT'));

ALTER TABLE reconciliation_exceptions DROP CONSTRAINT reconciliation_exceptions_type_check;
ALTER TABLE reconciliation_exceptions ADD CONSTRAINT reconciliation_exceptions_type_check
    CHECK (type IN ('MISSING_INTERNALLY', 'MISSING_AT_PROVIDER', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH', 'DUPLICATE',
                    'SETTLEMENT_MISMATCH', 'UNMATCHED_ADJUSTMENT'));

-- Net PSP adjustments in a run's window (credits minus debits).
ALTER TABLE reconciliation_runs ADD COLUMN adjustment_amount bigint NOT NULL DEFAULT 0;
