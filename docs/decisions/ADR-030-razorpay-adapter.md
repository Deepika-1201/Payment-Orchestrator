# ADR-030: Razorpay adapter

**Status:** Accepted (2026-09-27)

## Context
Phase 10 replaces the mock PSPs with real ones behind the provider SPI (ADR-005, ADR-014). Razorpay is first. Four facts about Razorpay shaped the design:

- **UPI collect is gone.** NPCI has deprecated UPI Collect since 28 Feb 2026, and Razorpay only enables server-to-server (S2S) UPI intent for a platform on request. Most accounts can take UPI only on a Razorpay-hosted page.
- **Idempotency keys are business fields.** Razorpay has no idempotency header. An order's `receipt`, a Payment Link's `reference_id` and a refund's `receipt` must be unique, and a repeat fails with "Duplicate request…" or "payment link creation with reference ID already attempted". Each can be up to 40 characters; our ids are 30.
- **A failed payment is not final.** Razorpay can capture a UPI payment it first reported as `failed`, for example when the customer retries in the UPI app.
- **Webhooks are per account.** Each merchant's Razorpay account signs its webhooks with its own secret: an HMAC-SHA256 hex of the raw body in `X-Razorpay-Signature`, and an id in `X-Razorpay-Event-Id`.

## Decision
- **Two initiation paths.**
  - *Hosted Payment Link:* the default for cards, netbanking, and UPI without S2S. The link has `reference_id` set to the attempt id and expires with the payment window (at least 16 minutes, since Razorpay requires at least 15). The customer is redirected to its `short_url`, and the link id is stored as the provider reference. The request sends no `customer` object: the hosted page doesn't prefill it, and some accounts reject a customer without a name.
  - *S2S UPI intent or QR:* used only when `pg.providers.razorpay.upi-s2s=true` and the payment has a customer phone and email. The adapter creates an order with `receipt` set to the attempt id, then calls `POST /payments/create/upi` with `flow=intent`. The returned `upi://` URI becomes the intent or the QR payload. The order id is the provider reference.
- **Timeouts are recovered by our own ids and never resent.**
  - A timed-out create leaves the attempt `UNKNOWN` (ADR-005). The status resolver then looks for a Payment Link by `reference_id`, then an order by `receipt`, comparing exactly because Razorpay's receipt filter matches substrings. If neither exists, the result is `NOT_FOUND`, which means never submitted. The link is checked first because a link's own order uses the same value as its receipt, and only the link reports `expired` or `cancelled`.
  - Duplicate errors on a retried create resolve to the existing entity.
  - Refunds carry `receipt` set to the refund id. `RefundStatusQuery` gained `paymentProviderReference`, because a timed-out refund has no refund reference and Razorpay lists refunds per payment.
- **Outcomes.**
  - `captured` or `refunded` maps to `SUCCEEDED`, and `authorized` to `AUTHORIZED`.
  - `failed` maps to `PENDING` until the payment window closes; after that it is `FAILED`, with the category taken from `error_source`.
  - An expired or cancelled link is `FAILED` with category `CUSTOMER`.
  - Card network and last 4 digits are taken from the payment.
- **Webhooks.** Only the per-account endpoint is accepted (`account == null` is rejected). The signature is compared in constant time against the account's `webhook_secret`, and the event id is required.
  - Mapped events: `payment_link.paid`, `payment_link.expired`, `payment_link.cancelled`, `payment.authorized`, `payment.captured`, `order.paid`, `payment.failed` (as `PENDING`), `refund.*` and `payment.dispute.*`. Others are ignored.
  - Payments are matched by our reference first, then by the attempt-id note, which Razorpay copies from links and orders onto payments. Disputes and payment events therefore match hosted-page payments too.
- **Capabilities are declared, not assumed.**
  - Supported: INR only, UPI intent (plus QR with S2S), cards, netbanking, and partial refunds.
  - Not supported: void (an authorization lapses at the issuer), and settlement reports, so reconciliation ignores this PSP until a report adapter exists.
- **Failure classification (ADR-005).**
  - Connect failure, TLS handshake failure or 429: unavailable, so the payment fails over.
  - Read timeout, a connection reset after sending, or a 5xx on a call that changes state: timeout, so the outcome is unknown. A 5xx on a read is unavailable.
  - 401, or a 400 "Authentication failed" / "api key provided is invalid": `ProviderCredentialsException`, which fails over without opening Razorpay's circuit.
  - Other 4xx: a rejection.
- **Credentials and environments.**
  - Each merchant links its own `key_id`, `key_secret` and `webhook_secret`. They are encrypted at rest and masked in responses (ADR-014).
  - The adapter refuses a key whose mode does not match the deployment (`rzp_test_` on TEST, `rzp_live_` on LIVE) before calling Razorpay. A test key in production would record payments that moved no money; a live key in a sandbox would charge customers.
  - In `prod`, the startup guard requires the base URL to be `https://api.razorpay.com/`, so misconfiguration cannot send merchant keys elsewhere. An egress proxy belongs in the network configuration, not in `base-url`.
- **Off by default.** The adapter is registered only when `pg.providers.razorpay.enabled=true`. `pg.providers.http.connect-timeout` and `.read-timeout` (2 s and 10 s) now apply to it.

## Alternatives

| Option | Trade-off |
|---|---|
| Razorpay Standard Checkout (JS) for everything | Needs our own checkout page to load Razorpay's script and a client-side signature step. Payment Links give a hosted page with nothing on our side, and the gateway's hosted checkout still works for UPI QR |
| Official Razorpay Java SDK | Pulls in `org.json` and its own HTTP stack, and hides the difference between "not sent" and "sent, no answer" that ADR-005 depends on. About 300 lines of `java.net.http` keep the classification explicit and testable |
| UPI collect (VPA) | Deprecated by NPCI since 28 Feb 2026 |
| Treat `payment.failed` as final | Would fail payments that Razorpay later captures. Those would then arrive as late successes and be auto-refunded, which is worse for the customer than waiting until the window closes |
| Provider-wide webhook endpoint with a platform secret | Razorpay signs with each account's own secret, and a platform secret would let one merchant's Razorpay account forge events for another (ADR-014) |
| Retry a timed-out create with the same `reference_id` | It works, but the resolver path is the same code the gateway already trusts for all PSPs, and a lookup never charges anyone |

## Consequences
- **Tests.**
  - `RazorpayPaymentProviderTest` runs against a local stub of Razorpay's REST API. It covers request shapes, duplicate recovery, the failure window, lookups after a timeout, refunds, error classification, webhook signatures (including Razorpay's SDK test vector) and event mapping.
  - `RazorpayGatewayIntegrationTest` runs the whole gateway against the stub: linking credentials, a card payment on the hosted page, forged and replayed webhooks, a refund and its webhook, and a timed-out link found by its reference and settled.
  - Mutation checks confirmed that the signature check, the failure window, the key-mode guard and the production URL pin each fail a test.
- **Needs live keys.** `RazorpaySandboxContractTest` checks what a stub cannot prove: that Razorpay's real duplicate errors are recognized, that lookup by attempt id works, and that a wrong secret is reported as a credentials failure. It runs only with `RAZORPAY_KEY_ID=rzp_test_…` and `RAZORPAY_KEY_SECRET`, and creates one link per run (test mode allows 30). It has not run yet: no sandbox keys are available to this project.
- **Operations.** Point each merchant's Razorpay webhook at the `webhook_path` returned by `PUT /admin/v1/merchants/{id}/provider-accounts/RAZORPAY`. Optionally restrict sources with `pg.webhooks.inbound.allowed-sources.RAZORPAY`, using the ranges Razorpay publishes.
- **Not yet done.** Reconciliation needs a settlement-report adapter (Razorpay's settlement recon API) before Razorpay payments are reconciled. Cashfree, the second PSP, followed in ADR-031.
