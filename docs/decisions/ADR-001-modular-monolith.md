# ADR-001: Modular monolith with `api` and `worker` roles

**Status:** Accepted (2026-09-26)

## Context
V1 must be correct, operable by a small team, and able to reach 1,000 TPS peak. The payment core (payments, attempts, refunds, idempotency) needs strong consistency, and several domains (webhooks, reconciliation, ledger) are natural candidates for later extraction.

## Decision
Build **one deployable** organized into business modules (`payment`, `provider`, `routing`, `risk`, `webhook`, `merchant`, `idempotency`, later `ledger`, `reconciliation`) with enforced boundaries. The same image runs in two roles: `api` (HTTP) and `worker` (background jobs), toggled by `pg.workers.enabled`.

## Alternatives
| Option | Pros | Cons |
|---|---|---|
| **Modular monolith** | Local ACID transactions across payment + outbox; one pipeline; simple debugging; cheap | Needs discipline to keep boundaries; a single blast radius for deploys |
| Microservices (10+ services) | Independent scaling and deploys | Distributed transactions/sagas for core money flows, a broker from day one, heavy ops for a small team |
| Service-oriented (3–4 services) | Some isolation | Still needs cross-service consistency for payment ↔ refund ↔ notification |

## Consequences
- Payment state, outbox rows, and audit logs commit atomically. There are no sagas in the core.
- ArchUnit tests enforce layering and module rules, so extraction later is mechanical.
- Extraction order when needed: webhook delivery → reconciliation → ledger (all already asynchronous and DB-decoupled).
