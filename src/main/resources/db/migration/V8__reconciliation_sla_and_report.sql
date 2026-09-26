-- Reconciliation exception ownership and SLA, and the daily report (ADR-017).

ALTER TABLE reconciliation_exceptions
    ADD COLUMN due_at      timestamptz,
    ADD COLUMN assignee    text,
    ADD COLUMN assigned_at timestamptz;

-- Existing exceptions get the default SLA (pg.reconciliation.exception-sla = 48h).
UPDATE reconciliation_exceptions SET due_at = created_at + interval '48 hours';
ALTER TABLE reconciliation_exceptions ALTER COLUMN due_at SET NOT NULL;

-- Overdue checks and the gauge only look at open exceptions.
CREATE INDEX ix_reconciliation_exceptions_open_due ON reconciliation_exceptions (due_at) WHERE status = 'OPEN';
CREATE INDEX ix_reconciliation_exceptions_run ON reconciliation_exceptions (run_id);

-- The daily report finds the runs of one exact window.
CREATE INDEX ix_reconciliation_runs_window ON reconciliation_runs (window_start, window_end);
