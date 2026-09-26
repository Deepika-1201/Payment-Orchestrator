-- Per-merchant API rate-limit overrides (ADR-020). NULL means the platform default (pg.rate-limit).

ALTER TABLE merchants
    ADD COLUMN rate_limit_read_per_second  double precision,
    ADD COLUMN rate_limit_read_burst       integer,
    ADD COLUMN rate_limit_write_per_second double precision,
    ADD COLUMN rate_limit_write_burst      integer,
    ADD CONSTRAINT ck_merchants_read_rate_limit CHECK (
        (rate_limit_read_per_second IS NULL AND rate_limit_read_burst IS NULL)
        OR (rate_limit_read_per_second > 0 AND rate_limit_read_burst >= 1)),
    ADD CONSTRAINT ck_merchants_write_rate_limit CHECK (
        (rate_limit_write_per_second IS NULL AND rate_limit_write_burst IS NULL)
        OR (rate_limit_write_per_second > 0 AND rate_limit_write_burst >= 1));
