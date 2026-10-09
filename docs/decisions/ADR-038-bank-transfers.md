# ADR-038: Bank transfers to a virtual account per attempt, with credits as their own records

**Status:** Accepted (2026-10-09)

## Context
Phase 21 (requirements §8.4, FR-VA1 to FR-VA3) lets a customer pay by pushing money: NEFT, RTGS or IMPS to a virtual account the PSP issues for the payment, or UPI to a virtual UPI ID. Unlike every method so far, the customer, not the gateway, decides how much arrives and when. A transfer can be short, too large, repeated, or sent after the payment ended. Facts that shaped the design, from Razorpay's Smart Collect documentation (2026-10-09):
- **Virtual accounts.** `POST /v1/virtual_accounts` creates a customer identifier: an account number and IFSC (a `bank_account` receiver), and with Smart Collect 2.0 a virtual UPI ID (a `vpa` receiver, live mode only). `close_by` closes it automatically, at least 15 minutes ahead; `POST /v1/virtual_accounts/{id}/close` closes it at once. Unused identifiers close after 90 days.
- **Credits are payments.** Each transfer becomes a captured Razorpay payment (`method: bank_transfer`, or `upi`) on the virtual account, reported by the `virtual_account.credited` webhook with the transfer's mode (`NEFT`, `RTGS`, `IMPS`, `UPI`) and bank reference (UTR), and listed by `GET /v1/virtual_accounts/{id}/payments`. Settlement reports list each credit as a payment, with its fee.
- **Closed accounts.** Razorpay refunds transfers to a closed identifier to their source by itself, usually within a business day.
- **No idempotency key.** Creating a virtual account takes no reference Razorpay can be searched by. Our attempt id travels only in `notes`, which webhooks repeat.

The rails are push payments. NEFT and RTGS run 24×7 (RBI, since December 2019 and December 2020), RTGS starts at ₹2 lakh, and IMPS is capped at ₹5 lakh per transfer. Once a credit lands in the PSP's account, the schemes' rules for returning a transfer to an unknown beneficiary no longer apply: sending it back is a refund. FR-VA cites no other RBI or NPCI rule.

## Decision
- **Method and next action.** `BANK_TRANSFER` is a new method type (`payment_method: {type: bank_transfer}`). Confirming creates one virtual account for the attempt, closing at the payment's expiry. `next_action.type = bank_transfer` carries the account number, IFSC, beneficiary name, bank name and, where issued, the UPI ID. The attempt's provider reference is the virtual account.
- **Credits are recorded once, as their own rows.** A credit (`trc_…`) is keyed by the PSP's id for it, whether it arrives by webhook or by the status check that polls the open account. It keeps the amount, mode and UTR, and how much of it was applied to the payment and how much is going back.
- **Allocation under the payment lock.** While the payment awaits the transfer, a credit counts toward it. When credits reach the amount, the attempt succeeds for exactly the amount and the excess goes back. Two settings per merchant:
  - `bank_transfer_credits`: `add_up` (default) lets several credits add up; `exact` accepts only a credit of exactly the amount and sends any other back at once.
  - `bank_transfer_short_at_expiry`: `refund` (default) sends credits back when the payment expires short; `accept` instead succeeds the payment for what arrived (amount captured below the amount, as in phase 19), for payers that withhold tax at source.
  - An excess is always sent back: the captured amount never exceeds the payment amount, and holding customer money would need a customer balance, which is not planned.
- **Late and repeated credits.** A credit to an attempt that is no longer waiting goes back in full: the payment succeeded, expired, failed or was cancelled. A second report of the same credit changes nothing.
- **Unknown accounts.** A credit to a virtual account the gateway cannot place is recorded and queued for review, not sent back. Merchants may run Smart Collect for other purposes on the same account, and returning their money would break those flows.
- **Returns are refunds.** Sending a credit back creates a refund marked `initiated_by: system_credit_return`, sent against that credit's PSP payment. It is resolved, retried and reconciled like any refund, and it does not count against the payment's `amount_refunded` or its refundable amount. A return the PSP refuses is queued for review.
- **Merchant refunds** of a transfer-paid payment are sent against one credit, as the PSP refunds per payment. A refund larger than what any single credit can still give back is refused, with the largest possible amount in the message.
- **Ledger.** Money that arrives but does not pay for anything yet is customer money. A new liability account, `customer_funds`, receives every credit. Funding the payment moves the captured amount to `sales_clearing`, in place of the usual capture posting, and a return takes the rest back out.
- **Reconciliation.** Each credit is an expected payment line, matched by its PSP id and amount; returns are expected refund lines. The attempt is not a line of its own.
- **Mock PSP.** `MOCK_ALPHA` issues accounts and UPI IDs; the simulator credits them in any amount and mode. Unlike Razorpay, the mock does not return transfers to closed accounts itself, so the gateway's handling of late credits can be tested.
- **Razorpay.** Smart Collect behind `extra-methods: bank_transfer`, with bank account receivers only (UPI IDs need Smart Collect 2.0, available live only). Returns use the normal refund API. Whether it refunds a bank transfer without the payer's account details is confirmed with sandbox keys (C1).

## Alternatives

| Option | Trade-off |
|---|---|
| Each credit as a payment attempt | Reuses attempt states, but a short credit is not a failed try, and duplicate-success refunds would fire for every second credit |
| Sum credits on the attempt only (no credit rows) | Fewer tables, but the PSP refunds and reports per credit, so returns, merchant refunds and reconciliation all need the credit's own id and amount |
| One virtual account per customer, reused across payments | What Smart Collect is built for, but credits would have to be matched by amount and time, which is guesswork; one account per attempt makes the match exact |
| Keep an excess or a short credit for a later payment | Needs a customer balance with its own lifecycle and ledger, which is not planned |
| Return credits to unknown accounts automatically | Satisfies FR-VA3 literally, but may send back money that belongs to the merchant's other integrations |
| Split a merchant refund across several credits | No refusal for large refunds, but one refund would become several PSP refunds with partial failure. The merchant sees each credit and can split the refund |

## Consequences
- `MethodType.BANK_TRANSFER`, `NextAction` bank details, and in the provider SPI a credit event, `fetchCredits` and `closeCollection`. `InitiatePaymentRequest` carries the payment's expiry.
- V17 adds `transfer_credits`, `refunds.credit_id`, the two merchant settings, the `system_credit_return` initiator, the `customer_funds` account and three credit postings.
- `payment/domain` gains credit allocation and the short acceptance on `Payment`.
- `GET /v1/payments/{id}/credits` lists a payment's credits; refunds show `credit_id`.
- The hosted checkout does not offer bank transfers yet: it would need a page that shows the account details.
- Revisit when a PSP lets the gateway preselect the amount on the account (`amount_expected`), when merchants need a customer balance, or when Cashfree's virtual accounts are needed.
