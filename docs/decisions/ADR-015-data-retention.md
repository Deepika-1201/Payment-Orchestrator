# ADR-015: Data retention by batched deletes; financial records are never deleted by the application

**Status:** Accepted (2026-09-26)

## Context
NFR-16 sets retention periods:
- operational data: raw PSP webhooks 180 days, merchant events and deliveries 90 days, idempotency keys 7 days;
- financial records (payments, attempts, refunds, transitions, ledger, audit, reconciliation): at least 8 years.

Nothing deleted the operational rows. The existing purges took 10,000 rows per hour, which is far less than the target volume: 1M payments/day means about 2M idempotency keys and 3M merchant events per day. Deleting from large, busy tables must not hold long transactions or locks.

## Decision
- **One `RetentionJob`, run hourly by the worker role.**
  - It deletes each data set in batches of `pg.retention.batch-size` (5,000). Every batch is its own short statement (`DELETE … WHERE id IN (SELECT … LIMIT n)`).
  - A run stops after `max-batches-per-run` (200) batches per data set. Anything left over is picked up by the next run.
  - Row counts are logged and exported as `pg.retention.deleted{table}`.
- **What gets deleted:**
  - idempotency records past `expires_at`;
  - checkout sessions 7 days after they expire;
  - PSP webhook events older than `pg.retention.provider-webhooks` (180 d), but only once handled (`PROCESSED`, `IGNORED`, `FAILED`);
  - merchant deliveries older than `pg.retention.merchant-events` (90 d), once `SUCCEEDED` or `DEAD`;
  - merchant events of that age that no longer have any delivery.
- **Unfinished work is never purged:** inbox events still being retried stay, and so do pending deliveries and their events.
- **Indexes:** each purged table has an index on its time column (V5 migration), so a batch is an index range scan, not a table scan.
- **Financial and audit tables are outside the job's reach.** Their 8-year retention, and archiving to cold storage after 13 months, are an operational process with its own controls, not application code.

## Alternatives
| Option | Trade-off |
|---|---|
| Monthly partitions, drop old partitions (pg_partman) | Near-free deletes and no bloat; needs partition management and partition-aware unique keys. Right at ~10× volume (architecture §5) |
| One `DELETE … WHERE created_at < cutoff` per table | Simple; a huge single transaction, lock and WAL spike at production volume |
| Time-to-live in a separate store | Not available in PostgreSQL; adds infrastructure (ADR-011) |
| **Batched deletes with time indexes** | Small, predictable batches; creates dead tuples that autovacuum must reclaim |

## Consequences
- **Operations:** autovacuum settings on the purged tables need attention in production, because of the dead tuples.
- **Replays:** webhook replay (`POST /admin/v1/webhook-deliveries/{id}/replay`) only works within the 90-day window.
- **Legal hold:** there is none yet. A dispute or regulator request needing old operational rows would require raising the retention settings first.
- **Moving to partitions later** changes only the storage side: the retention windows and the "never delete financial data" rule stay the same.
