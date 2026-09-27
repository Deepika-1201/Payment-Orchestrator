-- Backs the pg.attempts.unknown gauges scraped every few seconds (ADR-027); UNKNOWN attempts are few.
CREATE INDEX ix_attempts_unknown ON payment_attempts (created_at) WHERE status = 'UNKNOWN';
