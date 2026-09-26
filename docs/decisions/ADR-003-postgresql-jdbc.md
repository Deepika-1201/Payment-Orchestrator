# ADR-003: PostgreSQL as system of record, plain JDBC

**Status:** Accepted (2026-09-26)

## Context
Payment, refund, and ledger state need ACID transactions, row-level locking, rich constraints, and proven HA. The design point is 1,000 TPS peak (≈ 10k row writes/s) with a path to 10×.

## Decision
- PostgreSQL 17 (Aurora PostgreSQL in AWS) is the single system of record.
- Access is through Spring `JdbcClient` with explicit SQL. No ORM.
- Schema changes go through Flyway migrations (expand/contract).

## Alternatives
| Option | Pros | Cons |
|---|---|---|
| **PostgreSQL** | `FOR UPDATE SKIP LOCKED`, partial indexes, `jsonb`, CHECK constraints, triggers; Aurora HA/Global DB | Single writer; sharding is manual (Citus / app-level) |
| MySQL | Mature, widespread | Weaker partial-index/constraint support; `SKIP LOCKED` exists but tooling is thinner |
| Distributed SQL (CockroachDB, YugabyteDB, Spanner) | Horizontal writes, multi-region | Higher latency per transaction, cost and ops complexity not justified at 1k TPS |
| JPA/Hibernate | Less boilerplate | Hidden flushes and lazy loading make lock and transaction behaviour harder to reason about for money flows |

## Consequences
- Every invariant that can live in the DB does (`CHECK amount_refunded <= amount_captured`, unique PSP references, append-only triggers).
- `READ COMMITTED` + explicit row locks; no `SERIALIZABLE` retries needed.
- Scale path: bigger instance → partition append-heavy tables → shard by `merchant_id` (all tables already carry it).
