# ADR-005: Capability-based provider SPI with failure classification

**Status:** Accepted (2026-09-26)

## Context
PSPs differ widely: some support separate auth/capture, void, or partial refunds and some don't; UPI and netbanking are single-step; webhook formats and signature schemes differ. The most dangerous integration bug in payments is treating a **timeout** as a **failure** and retrying elsewhere, which can charge the customer twice.

## Decision
- One `PaymentProvider` SPI (initiate, status, capture, void, refund, refund status, parse webhook) plus a **capabilities descriptor** (methods, UPI flows, currencies, amount limits, manual capture, void, partial refunds). Routing and the orchestrator consult capabilities, never `instanceof`.
- Adapters normalize PSP responses to a small outcome set (`REQUIRES_ACTION, PENDING, AUTHORIZED, SUCCEEDED, FAILED, VOIDED, NOT_FOUND`).
- Adapters **classify failures**:
  - `ProviderUnavailableException`: the request definitely was not processed. Failover is allowed.
  - `ProviderTimeoutException`: the outcome is unknown. The attempt becomes `UNKNOWN` and failover is forbidden.
  - Business declines are `FAILED` results with a category.
- `ProviderClient` adds per-provider circuit breakers, bulkheaded HTTP pools, and metrics.

## Alternatives
| Option | Trade-off |
|---|---|
| Fixed interface where every method is mandatory | Forces fake implementations (e.g. "capture" for UPI) and hides real behaviour |
| One interface per capability (`Capturable`, `Voidable`, …) | Type-safe, but routing still needs data (limits, flows), so a descriptor is needed anyway |
| **Single SPI + descriptor** | Simple for adapter authors; capability checks are explicit and data-driven |

## Consequences
- Adding a PSP means writing an adapter, declaring capabilities, and adding config. The core domain does not change.
- Attempt and refund ids are passed to PSPs as merchant references / idempotency keys, which enables crash recovery through status lookup.
- Each adapter ships with contract tests against recorded sandbox fixtures (Phase 10).
