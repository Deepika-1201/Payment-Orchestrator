-- Partial capture (requirements §8.2, ADR-036): the amount captured, or being captured, per attempt.

ALTER TABLE payment_attempts ADD COLUMN capture_amount bigint;

-- V1 captured only full amounts.
UPDATE payment_attempts SET capture_amount = amount WHERE status IN ('CAPTURE_PENDING', 'SUCCEEDED');

ALTER TABLE payment_attempts ADD CONSTRAINT ck_attempts_capture_amount CHECK (
    CASE WHEN status IN ('CAPTURE_PENDING', 'SUCCEEDED') THEN coalesce(capture_amount BETWEEN 1 AND amount, false)
         ELSE capture_amount IS NULL
    END);
