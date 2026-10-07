# ADR-036: Partial capture: one capture up to the authorized amount, where the PSP supports it

**Status:** Accepted (2026-10-07)

## Context
Phase 19 (requirements §8.2, FR-P10) lets a merchant capture less than it authorized, for example when part of an order cannot be shipped. V1 captured only the full amount (FR-P5). FR-P10 cites no RBI or NPCI rule. PSP behavior, as confirmed on 2026-10-07:
- **Razorpay** requires the capture amount to equal the authorized amount. The adapter never authorizes without capturing anyway (ADR-030).
- **Cashfree** voids and captures only pre-authorizations, which need account enablement; the adapter captures automatically (ADR-031).
- **PSPs that offer it** (Stripe's documentation, for example): a capture may take less than the authorization, and the PSP releases the remainder as part of that capture. Most payments allow one capture only, so the remainder cannot be captured later. Capturing more than the authorization is a separate feature, limited to certain card payments.

Today, therefore, only the mock PSPs authorize without capturing.

## Decision
- **One capture, at most the authorized amount.** `POST /v1/payments/{id}/capture` takes an optional `amount` from 1 to the authorized amount; without it, the full amount is captured. A larger amount is refused with 422 `capture_amount_mismatch`. A payment is captured once: there is no second capture for the remainder.
- **A per-method capability.** `MethodSupport.partialCapture` declares that a PSP can capture less than the authorization for that method. A smaller amount for a PSP without it is refused with 422 `unsupported_payment_method` before any PSP call, as partial refunds are. `MOCK_ALPHA` cards support it. `MOCK_BETA` cards support manual capture without it.
- **The PSP releases the remainder.** The gateway makes no separate void call for it.
- **The attempt records its capture amount.** `payment_attempts.capture_amount` is set when a capture is requested, by the merchant or automatically (full amount). It is also set when a PSP reports a capture nobody requested, which can only be for the full amount. A capture reported for any other amount sends the attempt to review (`amount_mismatch`), as in V1. A rejected capture clears it, so the merchant can capture again, with the same or another amount.
- **Downstream uses the captured amount:** `payments.amount_captured`, the ledger's capture posting, the refundable amount, system refunds, the dispute check against the net captured amount, and the amount reconciliation expects in settlement reports.
- **Routing ignores it.** Whether a merchant will capture less is unknown when the payment is routed.

## Alternatives

| Option | Trade-off |
|---|---|
| Several partial captures (split shipments) | Few PSPs offer it (Stripe's multicapture, on some cards only). It needs a capture entity with its own lifecycle, ledger postings and reconciliation lines. A merchant can create one payment per shipment instead |
| On PSPs without partial capture, capture in full and refund the difference | Works everywhere, but the customer is charged in full and refunded days later, and some PSPs keep their fee on the full amount. A merchant can still choose to do it with a full capture and a partial refund |
| Void the remainder after the capture | No PSP needs it, and most refuse a void after a capture |
| Route manual-capture payments only to PSPs with partial capture | Narrows routing for every manual-capture payment, although few are captured partially. A creation flag could ask for it if merchants need it |
| Allow capturing more than authorized (tips, overcapture) | Network-specific, limited to some merchant categories, and not required |

## Consequences
- New column `payment_attempts.capture_amount` (V15). A database check requires it, between 1 and the attempt amount, for `CAPTURE_PENDING` and `SUCCEEDED` attempts, and requires it to be empty in every other status. Existing captured attempts are backfilled with their full amount.
- A payment's `amount_captured` can now be below its `amount`: the difference was released at the PSP. The API and the `payment.succeeded` event carry it unchanged.
- Partial capture works only on the mock PSPs until a real adapter with manual capture declares it.
- Revisit when a real adapter with manual capture is added (Cashfree pre-authorization, or an acquirer), or when merchants need split shipments.
