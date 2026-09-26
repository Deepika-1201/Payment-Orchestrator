-- Review queue and risk decisions (ADR-016).
-- A review flag now records why it was raised and when, so operations can work a queue instead of
-- querying for a bare boolean. The risk engine's decision is kept on each attempt for audit.

ALTER TABLE payment_attempts
    ADD COLUMN review_reason text,
    ADD COLUMN flagged_at    timestamptz,
    ADD COLUMN risk_outcome  text,
    ADD COLUMN risk_reasons  text;

ALTER TABLE refunds
    ADD COLUMN review_reason text,
    ADD COLUMN flagged_at    timestamptz;

-- Rows flagged before this migration have no recorded reason.
UPDATE payment_attempts SET review_reason = 'unspecified', flagged_at = updated_at WHERE needs_review;
UPDATE refunds SET review_reason = 'unspecified', flagged_at = updated_at WHERE needs_review;

-- The open queue is tiny compared with the tables; partial indexes keep it cheap to list and count.
CREATE INDEX ix_attempts_open_review ON payment_attempts (flagged_at) WHERE needs_review;
CREATE INDEX ix_refunds_open_review ON refunds (flagged_at) WHERE needs_review;
