# ADR-009: DB-stored declarative routing rules + health scoring

**Status:** Accepted (2026-09-26)

## Context
Routing must change at runtime (without a deploy), support merchant-specific preferences, fail over away from unhealthy providers, and prefer providers with better success rates and latency.

## Decision
- Rules are rows in `routing_rules`: scope (global or merchant), priority, conditions (field/operator/value, ANDed), strategy (`PRIORITY`, `WEIGHTED`, `DYNAMIC`), targets, `allow_fallback`. They are managed through the admin API, cached per instance, and refreshed every 30 s or immediately on write.
- Candidates are filtered by capabilities and circuit-breaker state before rules apply.
- `DYNAMIC` ordering uses in-memory health: provider-attributable success rate over the last 100 final outcomes, plus an EWMA latency penalty.

## Alternatives
| Option | Trade-off |
|---|---|
| Hard-coded rules | Needs a deploy per change; rejected by requirements |
| Config file / feature flags only | Easy, but no per-merchant data model and weak audit |
| **DB rules (declarative JSON conditions)** | Runtime changes, auditable, simple evaluator (microseconds) |
| Rule engine (Drools, etc.) | Powerful; heavy, hard to test and reason about for a handful of conditions |
| ML / contextual bandits | Best long-term optimization; needs data volume we don't have yet |

## Consequences
- Health data is per instance. With traffic spread across N instances each still sees enough samples; a shared store (Redis) can come later.
- Only provider-attributable failures (`PROVIDER`, `PROVIDER_UNAVAILABLE`, `TIMEOUT`) lower a provider's score. Customer drop-offs and issuer declines do not.
- Rule evaluation is deterministic except the `WEIGHTED` first pick, which is injectable for tests.
