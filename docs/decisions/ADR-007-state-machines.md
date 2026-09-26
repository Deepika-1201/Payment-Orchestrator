# ADR-007: Separate Payment / Attempt / Refund state machines, row-locked aggregate

**Status:** Accepted (2026-09-26)

## Context
The initial brief proposed one status enum containing payment, refund, and dispute states (`SUCCESS`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `DISPUTED`, …) and a linear auth → capture lifecycle. That breaks with concurrent partial refunds, with methods that have no separate authorization (UPI, netbanking), and with customers retrying a different method in the same checkout.

## Decision
- **Payment** is the merchant's intent. Its status is derived from the active attempt plus payment-level commands (cancel, expire).
- **PaymentAttempt** is one try at one PSP, with its own states including `UNKNOWN` and `CAPTURE_PENDING`.
- **Refund** is a separate aggregate with its own lifecycle. The payment exposes `amount_refunded`.
- Disputes (later) will be a separate aggregate too.
- The Payment is the aggregate root for its attempts. Every mutation runs as `SELECT … FOR UPDATE` on the payment row → apply → save (with a version check).
- Transitions are monotonic; PSP contradictions are flagged, never applied. Late and duplicate successes are handled explicitly (policy-driven refunds).

## Alternatives
| Option | Trade-off |
|---|---|
| Single status enum | Simple to display; wrong under concurrent refunds and retries |
| Optimistic locking only | No lock waits, but webhook/API races need retry loops everywhere |
| **Pessimistic row lock per payment** | Serializes the (rare) contention on one payment; no cross-payment contention; simple mental model |
| Event sourcing | Full history, but expensive to build and operate for V1; the append-only transition log gives most of the audit value |

## Consequences
- Webhook-before-response and duplicate webhooks converge to the same final state.
- Every transition is written to `payment_transitions` (append-only) with its source.
- Capabilities decide whether `AUTHORIZED` or `CAPTURE_PENDING` occur for a method.
