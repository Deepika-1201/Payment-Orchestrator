# ADR-018: Disputes and chargebacks: tracked from PSP evidence, ledger impact and refund guard

**Status:** Accepted (2026-09-26)

## Context
FR-D1 asks for three things:
- ingest chargebacks and UPI disputes from PSP webhooks and settlement reports;
- track their status;
- record their ledger impact and send merchant events.

The evidence workflow (uploading documents, accepting liability) is out of scope for V1. It happens on the PSP's dashboard.

In the orchestrator model (ADR-012) the PSP withholds a disputed amount from the merchant's settlements. It returns the amount if the merchant wins. Without dispute tracking:
- settlement reports with chargeback deductions would fail reconciliation;
- the ledger would not explain the missing money;
- a merchant could refund a customer who has already been made whole by a chargeback, and pay twice.

ADR-007 says disputes are their own aggregate, never a payment status.

## Decision
- **`Dispute` is an aggregate in the payment module**, next to refunds. It is created and changed only under the disputed payment's row lock, like refunds, so dispute and refund decisions serialize. It references the disputed attempt.
- **Lifecycle:** `OPEN → UNDER_REVIEW → WON | LOST`, and `OPEN` can also go directly to `WON` or `LOST`.
  - A first report may already be final.
  - A lower-ranked report after a higher one (for example `under_review` after `won`) is stale and ignored.
  - A contradicting final report (`won` after `lost`) is not applied. The dispute is flagged for review with reason `provider_conflict`.
  - Pre-arbitration reversals are therefore handled by operations, not modelled.
- **Sources of truth:**
  - **PSP webhooks** (`ProviderEvent.Kind.DISPUTE`).
  - **Settlement reports** (`CHARGEBACK` and `CHARGEBACK_REVERSAL` lines). A chargeback line with no known dispute records the dispute from the report: `AUTO_HEALED`, reason `reported_in_settlement`. A reversal line heals the dispute to `WON`. Unknown payments become `MISSING_INTERNALLY`, and amount differences become `AMOUNT_MISMATCH`.
  - **Payout expectation:** captures − fees − refunds − chargebacks + reversals.
- **Tenant isolation** works as for refunds (ADR-014):
  - A dispute webhook arriving on a merchant's account endpoint can only reach that merchant's payments.
  - Dispute ids are unique per `(provider, merchant, provider dispute id)`, so one account cannot claim another merchant's ids.
- **Ledger:** the disputed amount is assumed withheld when the dispute opens.
  - **Open:** Dr `CHARGEBACKS` / Cr `PSP_RECEIVABLE`, reference `DISPUTE:<id>`.
  - **Won:** reversal, Dr `PSP_RECEIVABLE` / Cr `CHARGEBACKS`.
  - **Lost:** no further posting. The chargeback stands.
  - Postings commit in the same transaction as the status change (a `FundsMovement`, like captures and refunds), and they are idempotent by reference.
- **Refund guard:** refundable = captured − active refunds − disputes not won (`OPEN`, `UNDER_REVIEW`, `LOST`).
  - The undisputed remainder stays refundable.
  - A won dispute releases its amount.
- **A dispute larger than the net captured amount is still recorded, because the PSP withholds it anyway.** It is flagged `amount_exceeds_net_captured`, for example when the customer had already been refunded, so operations can help the merchant respond.
- **Merchant API:** read-only.
  - Endpoints: `GET /v1/disputes/{id}` and `GET /v1/payments/{id}/disputes`.
  - Fields: `respond_by` (the PSP's deadline) and `provider_reference` (the dispute id at the PSP).
  - Events: `dispute.created`, `dispute.updated` (under review), `dispute.won`, `dispute.lost`.
  - Everything is in the OpenAPI contract.
- **Review queue:** disputes are a third kind (`/admin/v1/reviews?kind=dispute`, `POST …/disputes/{id}/resolve`), resolved with an acknowledgement only (ADR-016).
- **Metrics:** `pg.disputes.opened{provider}`, `pg.disputes.closed{provider,outcome}`, `pg.provider.conflicts{source=dispute}`.

## Alternatives
| Option | Trade-off |
|---|---|
| Dispute as a payment status (`DISPUTED`) | Simple to read; breaks with partial disputes, several disputes per payment, and refunds during disputes (ADR-007) |
| Post the chargeback only when the dispute is lost | Matches PSPs that debit late; wrong for PSPs that withhold at opening (the common case in India), where the ledger would not match the settlement. Adapters for late-debit PSPs can map their events to `OPEN` at the debit |
| Block all refunds while any dispute is open | Simplest guard; needlessly blocks refunding the undisputed part of a partially disputed payment |
| Allow `won ↔ lost` transitions (pre-arbitration) | Models card-network reality; needs repeated chargeback postings and phase tracking, which is not worth it before real volumes |
| Evidence submission through the gateway API | A full dispute workflow; out of scope for V1 (requirements §7), and evidence formats are PSP-specific |

## Consequences
- **Chargebacks in the ledger:** the `CHARGEBACKS` balance per merchant PSP account is the net amount lost to disputes. Finance can see it next to refunds and fees.
- **Reconciliation:** reports that deduct chargebacks now reconcile to a zero receivable, and disputes the PSP never notified are still recorded.
- **Merchants must respond at the PSP before `respond_by`.** The gateway only reports status; dispute events are the prompt to act.
- **Real adapters** (Phase 10) must map their dispute states to the four statuses and emit settlement lines with the dispute id and the disputed attempt reference.
