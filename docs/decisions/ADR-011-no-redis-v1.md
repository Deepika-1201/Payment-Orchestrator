# ADR-011: No Redis in V1

**Status:** Accepted (2026-09-26)

## Context
Typical uses of Redis in gateways: idempotency locks, rate limiting, caching merchant/API-key lookups, shared routing-health windows.

## Decision
No Redis in V1:
- **Idempotency and locks:** PostgreSQL (the source of truth anyway).
- **Routing health:** in-memory per instance.
- **Rate limiting:** WAF rate-based rules at the edge; a per-instance token bucket in the app (later phase).
- **Caching:** API-key and rule lookups are indexed point reads; an in-process cache comes if profiling shows a need.

## Alternatives
| Option | Trade-off |
|---|---|
| ElastiCache (Redis/Valkey) from day one | Precise global rate limits, shared health; another HA component, and a second source of truth for locks |
| **Postgres + in-memory** | Fewer moving parts; limits and health are approximate across instances |

## Consequences
- Rate limits are approximate (per instance × N). Acceptable for V1 traffic.
- Revisit when: precise per-merchant quotas are contractual, hot-path DB reads exceed ~20% of DB CPU, or routing needs a globally consistent view.
