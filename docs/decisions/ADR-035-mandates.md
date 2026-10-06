# ADR-035: Recurring payments with gateway-scheduled mandate debits

**Status:** Accepted (2026-10-06)

## Context
Phase 18 adds recurring payments (requirements §8.1): UPI AutoPay, card e-mandates and eNACH. The rules that shape the design, as confirmed on 2026-10-06 against Razorpay's documentation (NPCI's pages were unavailable):
- **Registration:** the customer authorizes a mandate at the PSP with an additional factor. For UPI and cards the authorization is a real payment of at least ₹1, which the PSP captures and settles. eNACH registrations move no money.
- **Notifications:** card and UPI debits need a pre-debit notification at least 24 hours before the debit. Razorpay creates one per order (`notification.token_id`, `payment_after`) and does not retry a debit placed on such an order.
- **Limits:** debits without an additional factor are capped at ₹15,000, or ₹1,00,000 for mutual funds, insurance premiums and credit card bills. UPI mandates allow up to ₹1,00,000 (some categories ₹2,00,000); eNACH up to ₹1 crore.
- **Retries:** PSPs retry failed UPI and card debits on the next three days. A new debit should not be started while the previous one's result is unknown, and UPI results can take 24–36 hours.

## Decision
- **Mandate aggregate.** `Mandate` lives in the payment module next to disputes (ADR-018), with its own state machine:
  - `CREATED → PENDING_AUTHORIZATION → ACTIVE ⇄ PAUSED`, ending in `REVOKED`, `EXPIRED` or `FAILED`.
  - Transitions are monotonic by rank and logged in an append-only `mandate_transitions` table.
  - Customer pauses and revocations arrive by PSP webhook or status check.
- **Debits are payments.** A debit creates a payment with `mandate_id` and a per-payment attempt limit (1 + retries). Its attempts use the payment method `mandate`. Outcomes, late successes, refunds, disputes, the ledger and reconciliation therefore work unchanged. A `mandate_debits` record schedules the debit: `SCHEDULED → NOTIFYING → READY → EXECUTING`, ending in `SUCCEEDED`, `FAILED` or `CANCELLED`.
- **The gateway schedules.** Notification and execution are driven by a gateway worker rather than a PSP's subscription engine:
  - **Notified cycles:** each execution cycle, the first and every retry, sends its own pre-debit notification. The attempt executes no earlier than 24 hours after delivery.
  - **Checks in two places:** the code checks an `ACTIVE` mandate, the amount limits and the 24-hour floor at execution. The database rejects any execution that breaks them (NFR-19).
  - **One debit at a time:** a mandate has at most one debit in progress.
  - **Retries:** a failed attempt is retried up to `pg.mandates.max-retries` (3) times, a day apart. After the last failure the payment fails with `payment.failed`.
- **Registration payments.** When the PSP charges for authorization (UPI and cards), the gateway records the charge as the mandate's registration payment, with one attempt and no retries. The ledger and reconciliation then account for it like any capture, and the merchant can refund it.
- **No routing or risk per debit.** A mandate is bound to the PSP that registered it, so its debits go to that PSP without failover. Mandate creation picks the first linked, available PSP that supports the instrument. Risk rules are not run: the customer authenticated at registration, and debits stay within the authorized limits.
- **Limits.** The frictionless debit limit defaults to ₹15,000 (`pg.mandates.frictionless-debit-limit`). An operator can raise it per merchant up to ₹1,00,000 (`mandate_debit_limit` on `PATCH /admin/v1/merchants/{id}`) after checking the merchant's category. eNACH debits are limited only by the mandate.
- **Real PSP.** Razorpay is the first real adapter:
  - registration links (`/subscription_registration/auth_links`);
  - tokens: status, cancellation and `token.*` webhooks;
  - orders with a `notification` object (`order.notification.*` webhooks);
  - `/payments/create/recurring`.
  Cashfree follows when needed.

## Alternatives

| Option | Trade-off |
|---|---|
| PSP subscription engines (Razorpay Subscriptions, Cashfree Subscriptions) | Less code, but every PSP models plans and retries differently. The gateway would lose control of limits and notification timing, and could not move a merchant between PSPs |
| Debits as their own entity instead of payments | Would duplicate attempts, outcomes, refunds, disputes, ledger postings and reconciliation for a second money-moving entity |
| Reuse one notification for all retries | Fewer messages, but Razorpay does not retry debits on a notified order, and stale notices risk debits the customer was not warned about. One notification per cycle is the conservative reading |
| Ignore the ₹1 registration charge | Simpler, but every UPI or card mandate would leave an unexplained capture in settlement reports (a reconciliation exception per mandate) |
| A per-mandate category that raises the limit | Merchants would self-declare their category. A per-merchant limit set by an operator matches how PSPs assign categories (by merchant category code) |

## Consequences
- New tables `mandates`, `mandate_debits` and `mandate_transitions`. New columns `payments.mandate_id`, `payments.attempt_limit` and `merchants.mandate_debit_limit`. The method type `MANDATE` is added for attempts (V14).
- New merchant API under `/v1/mandates` (OpenAPI), and events `mandate.activated`, `mandate.paused`, `mandate.resumed`, `mandate.revoked`, `mandate.expired` and `mandate.failed`.
- A UPI or card debit executes at least 24 hours after its notification reaches the customer, and each retry waits for a new one. A debit therefore completes a day or more after it is requested, and its retries follow one per day.
- Payments with a `mandate_id` cannot be confirmed or cancelled through the payments API. Debits are cancelled through the mandate API.
- Mocks follow Razorpay: UPI and card registrations charge ₹1, eNACH none. Scenarios are selected by amount suffix as for payments (LLD §18.8).
