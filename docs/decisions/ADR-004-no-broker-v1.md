# ADR-004: No message broker in V1 — outbox/inbox tables and DB-driven workers

**Status:** Accepted (2026-09-26)

## Context
We need reliable async work: merchant webhooks, PSP webhook processing, status polling, expiry, and later reconciliation. Launch volume is ~12 TPS average, 1,000 TPS peak. Every async job is triggered by a state change in PostgreSQL.

## Decision
No Kafka, SQS, or RabbitMQ in V1. State changes write outbox rows (`merchant_events`, `webhook_deliveries`) and inbox rows (`provider_webhook_events`) **in the same transaction**. Workers claim due rows with `FOR UPDATE SKIP LOCKED` + leases.

## Alternatives
| Option | Durability / ordering | Throughput | Ops complexity | Fit for V1 |
|---|---|---|---|---|
| **Postgres outbox + workers** | ACID with the state change; per-row ordering only | Thousands of rows/s with batching | None extra | Best: no dual-write problem, nothing new to run |
| Kafka (MSK) | Durable, partition ordering, replay | Very high | High (partitions, consumer groups, schemas) | Overkill until many consumers or streaming analytics |
| SQS/SNS | Durable, at-least-once, DLQ built in | High | Low (managed) | Good next step; still needs an outbox to avoid dual writes |
| RabbitMQ | Flexible routing | Medium-high | Medium | No advantage here |
| Redis Streams | Fast | High | Medium; durability depends on config | Weaker durability guarantees for money events |

## Consequences
- No dual-write risk: a notification exists if and only if the state change committed.
- Postgres carries the queue load. Mitigations: partial indexes on due rows, batch claims, delete/partition old rows, autovacuum tuning.
- Evolution: add a generic `outbox_events` table and a relay to SQS/SNS (or MSK) when more than one consumer needs the same events or when event volume starts to hurt OLTP.
