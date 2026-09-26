# ADR-006: Layered idempotency

**Status:** Accepted (2026-09-26)

## Context
Merchants retry on timeouts, PSPs redeliver webhooks, and our own workers can crash mid-job. None of this may create a second charge, a second refund, or a lost update.

## Decision
Three layers:
1. **API layer:** `Idempotency-Key` is required on every mutating endpoint, stored in PostgreSQL per `(merchant_id, key)` with a request fingerprint, in-progress lease, and stored response. Keys are kept 7 days. Behaviour: replay on match, `422` on a changed body, `409` while in progress, takeover after lease expiry. 5xx outcomes are not stored.
2. **Domain layer:** commands are idempotent by state. A write-ahead `INITIATED` attempt or refund exists before any PSP call. Transitions are monotonic, so duplicate or stale inputs are no-ops.
3. **PSP layer:** `att_…` / `rfnd_…` ids are sent as PSP idempotency keys / merchant references. Inbound webhooks are deduplicated by `UNIQUE(provider, event_id)`.

## Alternatives
| Option | Trade-off |
|---|---|
| Redis `SETNX` locks + cache of responses | Fast, but lost on failover; creates a second source of truth next to the DB |
| DB unique constraint on `merchant_order_id` only | Blocks legitimate retries of a failed order; no response replay |
| **DB-backed records + domain idempotency** | One extra insert per mutating call (cheap); survives crashes and failover |

## Consequences
- A lost response is recovered by retrying with the same key.
- Concurrent duplicates are serialized without distributed locks.
- Purging is a simple batched delete (or a dropped partition later).
