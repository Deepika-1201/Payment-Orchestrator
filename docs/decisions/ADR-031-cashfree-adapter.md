# ADR-031: Cashfree adapter

**Status:** Accepted (2026-09-27). Settlement reports were added later by [ADR-032](ADR-032-settlement-reports.md); Cashfree timestamps now always carry seconds.

## Context
Cashfree is the second real PSP, after Razorpay (ADR-030). Its PG API differs in ways that matter to the orchestrator:

- **Merchant-chosen ids are the idempotency keys.** An order's `order_id` (3–45 characters), a Payment Link's `link_id` (up to 50) and a refund's `refund_id` (3–40) are chosen by the merchant. Reusing one returns HTTP 409 (for example `order_already_exists`). Our attempt and refund ids are 30 characters.
- **Amounts are in rupees,** with two decimals, not in paise.
- **A customer phone is required.** Orders and links need `customer_details.customer_phone`, a 10-digit Indian mobile number.
- **Link payments happen on Cashfree's own orders.** A customer paying a Payment Link pays on an order Cashfree creates (`CFPay_…`). Payment and dispute webhooks carry that order id, and payment webhooks add only `order_tags.cf_link_id`.
- **Server-to-server needs approval.** Order Pay (seamless payments) must be enabled by Cashfree: GST turnover documents for UPI and netbanking, PCI DSS for cards.
- **Webhooks are signed with the client secret:** Base64(HMAC-SHA256(`x-webhook-timestamp` + raw body)). `x-idempotency-key` is a header outside the signature.
- **Keys don't show the environment.** Sandbox and production are separate hosts, and the keys themselves carry no test or live marker.

## Decision
- **Hosted Payment Link by default.** The link's `link_id` is the attempt id, and it expires with the payment window (at least 16 minutes). The customer is redirected to `link_url`. The attempt's provider reference is `cflink_<cf_link_id>`, which is what payment webhooks can be matched on. With `pg.providers.cashfree.upi-s2s=true`, UPI intent or QR goes through Create Order (`order_id` = attempt id) and Order Pay (`upi.channel` = `link` or `qrcode`), and the order id is the reference.
- **Timeouts are recovered by our ids.** After a timeout, `GET /links/{attempt}` or `GET /orders/{attempt}` finds the attempt; two 404s mean it was never submitted. A 409 on create reads the existing link or order back.
  - A link's outcome is decided by its status (`PAID`, `EXPIRED`, `CANCELLED`), after checking that no order created for the link was paid, since a payment can complete as a link closes.
  - For S2S orders, a failed or dropped payment is final only once the order has `EXPIRED` or is `TERMINATED`, because the customer can pay again on an `ACTIVE` order.
- **Refunds** go to the order that was paid: our own order for S2S, or Cashfree's `CFPay_…` order found through `GET /links/{attempt}/orders`. Each refund carries `refund_id` = our refund id and is read back by it after a timeout. `RefundStatusQuery` gained the attempt id for this.
- **Amounts** are converted exactly: paise go out as `BigDecimal(paise, 2)`, and replies are read back with `RoundingMode.UNNECESSARY`. A reply that isn't whole paise is treated as missing, never rounded.
- **Phone-aware routing.** `ProviderCapabilities` gained `requiresCustomerPhone`, and routing skips such PSPs for payments without a phone. The alternative was letting them fail at the PSP (Cashfree answers `phone_missing`). The adapter normalizes `+91` or `0` prefixes. A number Cashfree would reject fails the attempt with `VALIDATION` before any call is made.
- **Webhooks.**
  - Only the per-account endpoint is accepted. The signature, including the timestamp, is compared in constant time.
  - Events are deduplicated on `sha256` of the signed body, never the unsigned `x-idempotency-key` header, so a replay can't dodge dedupe by changing a header. Dedupe is scoped to the merchant account (ADR-014) and lasts as long as the inbox retention (180 days).
  - No freshness window is enforced. Cashfree retries for hours and supports batch resends from its dashboard, and it doesn't document re-signing those, so a window could drop real deliveries.
  - Mapped events: `PAYMENT_SUCCESS_WEBHOOK` (succeeded); `PAYMENT_FAILED_WEBHOOK` and `PAYMENT_USER_DROPPED_WEBHOOK` (pending, not final); `REFUND_STATUS_WEBHOOK`; `DISPUTE_CREATED`, `DISPUTE_UPDATED` and `DISPUTE_CLOSED`.
  - Dispute statuses: `*_MERCHANT_WON` is won; `*_MERCHANT_LOST`, `*_MERCHANT_ACCEPTED` and `*_INSUFFICIENT_EVIDENCE` are lost; `*_DOCS_RECEIVED` and `*_UNDER_REVIEW` are under review; anything else is open. Retrieval requests move no money and are ignored.
  - A dispute on a `CFPay_…` order is matched by reading that order's `cf_link_id` tag.
- **Environment follows the deployment.** With no `base-url`, TEST uses `sandbox.cashfree.com/pg` and LIVE uses `api.cashfree.com/pg`. In `prod`, the startup guard refuses any other host, including the sandbox.
- **Failure classification (ADR-005).**
  - 401 or `authentication_error`: `ProviderCredentialsException`, which fails over without opening the circuit.
  - 429 or a connect failure: unavailable.
  - Read timeout, or a 5xx on a call that changes state: timeout, so the outcome is unknown. A 5xx on a read is unavailable.
  - Other 4xx: a rejection.
- **Scope.**
  - Supported: INR; UPI intent (plus QR with S2S), cards and netbanking; automatic capture only; partial refunds.
  - Not supported: void (Cashfree voids only pre-authorizations, which need account enablement), and settlement reports (reconciliation waits for a settlement-recon adapter).
  - The adapter sends `x-api-version: 2025-01-01`.

## Alternatives

| Option | Trade-off |
|---|---|
| Hosted checkout through Cashfree's JS SDK (`payment_session_id`) | Needs a page of ours that loads Cashfree's script. Payment Links give a hosted URL with no front end, the same model as Razorpay (ADR-030) |
| Store the link id (`cf_link_id` or `link_id`) as the reference | Payment webhooks for links carry only `CFPay_…` and `cf_link_id`, so `cflink_<cf_link_id>` is the one value both sides know |
| Send a placeholder phone when none is known (Cashfree says dummy details are acceptable) | Hides a data gap and feeds made-up data into Cashfree's risk checks. Routing elsewhere is honest and costs nothing when another PSP is linked |
| Deduplicate on `x-idempotency-key` | Not covered by the signature, so an attacker replaying a captured body could bypass dedupe by changing it |
| Reject webhooks older than N minutes | Could drop Cashfree's retries and dashboard resends. The body-hash dedupe already stops replays for 180 days |
| Official Cashfree Java SDK | Brings its own HTTP stack and models, and hides the "not sent" vs "sent, no answer" distinction ADR-005 needs |

## Consequences
- **SPI changes.** `ProviderCapabilities` gains `requiresCustomerPhone` (the old constructor defaults it to false). `RoutingContext` gains `customerPhoneKnown`, and `RefundStatusQuery` gains `attemptId`. The shared test stub, `support/StubPsp`, now serves both adapters.
- **Tests.**
  - `CashfreePaymentProviderTest` runs against a stub of Cashfree's API. It covers request shapes and headers, exact rupee conversion, phone normalization, 409 reuse, links paid on Cashfree's orders, timeout lookups, closed vs active orders, refunds on the paid order, error classification, webhook signatures (timestamp included), tampering, and event and dispute mapping.
  - `CashfreeGatewayIntegrationTest` runs the whole gateway: phone-aware routing (refused, or routed to another PSP), a hosted-page card payment settled by a signed webhook, forged and replayed webhooks, a refund and its webhook, and a timed-out link recovered and settled.
  - Mutation checks confirmed that the signature check and the phone rule each fail tests.
- **Needs sandbox keys.** `CashfreeSandboxContractTest` checks against Cashfree's sandbox that its real 409 answer, lookup by attempt id and key rejection match what the stub assumes. It runs with `CASHFREE_CLIENT_ID` and `CASHFREE_CLIENT_SECRET` and has not run yet, because no keys are available to this project.
- **Operations.** Point each merchant's Cashfree webhook (version 2025-01-01) at the `webhook_path` returned when the account is linked.
