# ADR-032: Settlement reports from Razorpay and Cashfree

**Status:** Accepted (2026-10-05)

## Context
Reconciliation (FR-RC1..4, ADR-017, ADR-018) compares each merchant PSP account's settlement report with the gateway's records and its shadow ledger. Until now only the mock PSPs produced reports. ADR-030 and ADR-031 left the real PSPs out (`settlementReports = false`). Real reports differ from the mock's in five ways that matter:

- **They are dated by settlement, not by transaction.** Razorpay's settlement recon (`GET /settlements/recon/combined?year&month&day`) lists what was settled on a day. Cashfree's settlement reconciliation (`POST /settlement/recon`) lists each settlement's events for a date range. A capture usually settles T+2 working days later.
- **They hold more than our captures, refunds and chargebacks.** Both list adjustments: Razorpay has `adjustment` and `transfer` items, and Cashfree has `OTHER_ADJUSTMENT`, `RISK`, `REFUND_REVERSAL` and more. The gateway has no record of these.
- **Fees are not always withheld.** A merchant on postpaid pricing is invoiced for fees, so the payout carries the full amount. Instant refunds and transfers carry fees too.
- **Ids differ from ours.** Razorpay reports the payment `pay_…` with its `order_id` and `order_receipt`, while our attempt references a link (`plink_…`) or an order. Cashfree reports link payments on its own `CFPay_…` orders tagged with `cf_link_id`, names a refund only by `refund_id`, and names a dispute only by its order.
- **A payout can fail or still be pending** when the report is read.

## Decision
- **Both adapters now produce settlement reports**, so the daily T+1 job (ADR-017) reconciles their accounts too.
- **Windows are settlement windows.**
  - *Razorpay:* the adapter reads every India-time day the window touches, plus one day either side in case Razorpay dates a settlement differently. Each day is read in pages of 1,000 (`count`, `skip`). Items are kept when `settled_at` falls in the window; unsettled items are skipped.
  - *Cashfree:* the adapter asks for the window widened by a day on each side (API version 2025-01-01, `start_date`/`end_date`), follows the cursor, and keeps events by `settlement_details.settlement_date`. Events whose `event_status` isn't `SUCCESS` are skipped.
  - Page and cursor loops are bounded, and a repeated cursor fails the fetch instead of looping.
- **Settlement lag.** `PaymentProvider.settlementLag()` (default zero; the mock reports by transaction time) states how long the PSP may take to settle. A run for `[from, to)` flags `MISSING_AT_PROVIDER` only for items that succeeded in `[from − lag, to − lag)`, so every capture is checked once, when its settlement is overdue. The default is `5d` for both PSPs (T+2 working days plus weekends and holidays), set by `pg.providers.{razorpay,cashfree}.settlement-lag`. A late item is still auto-resolved when it appears (ADR-017).
- **Fees are what the PSP kept.**
  - Razorpay: the amount minus the credit on credit lines, or the debit minus the amount on debit lines.
  - Cashfree: the gap between `event_amount` and `event_settlement_amount`, or the reported charges when the settlement amount is missing.
  - Reconciliation now counts and posts fees on any line, not just captures: each line moves the payout by its signed amount minus its fee.
- **Adjustments.** `SettlementReport.LineType` gains `ADJUSTMENT_CREDIT` and `ADJUSTMENT_DEBIT`, carrying the PSP's description.
  - Each one opens an `UNMATCHED_ADJUSTMENT` exception.
  - It counts towards the payout the gateway expects, so the payout is not flagged a second time.
  - It is **not** posted to the ledger. `PSP_RECEIVABLE` keeps exactly that amount until finance explains it and books it as a maker-checker adjustment (ADR-024).
  - Runs report `adjustment_amount` (credits minus debits). Migration V13 extends the line and exception type checks.
- **Matching our records.**
  - *Razorpay:* a payment line uses the `order_id` as its reference, and the attempt id from `order_receipt` or the `pg_attempt_id` note. Payment Link payments match by the attempt id; healing sets the order id as the reference only if none was known. A refund line uses `rfnd_…` and the `pg_refund_id` note. An adjustment with a `dispute_id` is a chargeback (debit) or reversal (credit). Its attempt is found through the dispute's payment notes or the order receipt, so a chargeback known only from the report can be recorded (ADR-018).
  - *Cashfree:* a payment line uses `cflink_<cf_link_id>` for links or the order id for server-to-server UPI. A refund line uses our `refund_id`. For a dispute or chargeback, the adapter reads the order's disputes and takes the one with the reported amount. If none or several match, the line gets a placeholder reference and no attempt. It then becomes `MISSING_INTERNALLY` for a person to check, rather than recording a second dispute that could double the amount withheld.
- **Payout status.** A processed (`processed` / `SUCCESS`) payout is reported with its amount. A failed one is reported as zero: the payout check flags the whole net, and the receivable keeps it. A pending one (`created`, `PENDING*`) fails the fetch with "reconcile this window again once the PSP has paid it out", so nothing is booked that hasn't reached the bank.
- **Failed days are retried.** Before reconciling yesterday, the daily job reruns accounts whose latest run of one of the previous `pg.reconciliation.catch-up-days` (3) days failed. A pending payout or a PSP outage therefore heals by itself; older failures stay visible as `failed` in the daily report for an operator to rerun. Days without a run are not back-filled, because the account may not have existed then.
- **Report fetches bypass the circuit breaker and routing latency.** A report can take many pages and seconds; recording it would slow routing (ADR-009) or open the payment circuit. Failures still count in `pg.provider.call{operation=settlement_report}`. Adapter errors keep their message in the run's `error`. A refused request carries the PSP's reason.
- **Small fixes found on the way.** Cashfree timestamps (`link_expiry_time`, report dates) now always carry seconds, as in Cashfree's examples. Ids read from reports are URL-encoded before going into a path.

## Alternatives

| Option | Trade-off |
|---|---|
| Treat the window as transaction dates (like the mock) | Neither PSP lists items by transaction date for settlement; payouts would never line up with a window |
| Flag `MISSING_AT_PROVIDER` for the window itself and let late items auto-resolve | Every capture would sit in the exception queue for two or more days, and the overdue backlog would mean nothing |
| A settlement lag per merchant | More precise for merchants on T+1 or instant settlement, but the cost of a lag that is too long is only a later flag. One generous value per PSP is simpler to run |
| Book adjustments automatically to a suspense account | The money would look explained while nobody had checked it. An exception plus a maker-checker adjustment keeps a human decision on record |
| Use the reported fee (`fee`, `event_service_charge`) | Wrong for postpaid pricing, where fees are invoiced: every payout would mismatch |
| Report a pending payout as paid | The ledger would show money in the bank that may never arrive |
| Retry failed runs within the same night, or back-fill every day without a run | Same-night retries rarely help a payout that is pending until the bank confirms it the next day. Back-filling would create runs for days before an account existed |
| Match Cashfree disputes by amount across all of the merchant's disputes | Could attach a withheld amount to the wrong payment. The order narrows it to the right payment, and ambiguity goes to a person |
| Read Razorpay or Cashfree settlement files (SFTP or dashboard exports) | Needs an SFTP account and file parsing per PSP. The APIs give the same data with the merchant's existing keys |
| Official SDKs | Same reason as ADR-030/031: they hide the "not sent" vs "sent, no answer" distinction and add dependencies |

## Consequences
- **Tests.**
  - `RazorpayPaymentProviderTest` and `CashfreePaymentProviderTest` cover against the stubs:
    - the day/cursor reads, window filtering, pagination and its bounds;
    - every line type with its references and kept fees;
    - dispute resolution;
    - payout status handling;
    - exact rupee parsing;
    - refused and unauthorized requests.
  - `RazorpayGatewayIntegrationTest` and `CashfreeGatewayIntegrationTest` reconcile through the whole gateway:
    - a capture, a refund with a fee, a chargeback known only from the report, and an adjustment;
    - the resulting run totals and ledger, including the receivable residual;
    - `MISSING_AT_PROVIDER` held back until the lag has passed, then auto-resolved by a late settlement.
  - `ProviderClientTest` shows that failing or slow reports neither open the circuit nor change routing latency.
  - `ReconciliationOperationsIntegrationTest` shows that a failed day is retried within the catch-up window, and that completed or older days are not.
  - 28 mutants of the new rules were each killed by a test.
- **Needs sandbox keys.** The sandbox contract tests now also fetch a settlement report, which proves the request shapes and authentication. The following are documented but unverified until keys exist:
  - which time zone Razorpay uses for `day` (mitigated by the extra days);
  - Cashfree's field names in version 2025-01-01 (parsed tolerantly: `utr` or `settlement_utr`, object or array details);
  - that a Cashfree recon `refund_id` is ours;
  - the settlement status values.
- **Operations.**
  - The first runs after enabling a PSP can flag captures that settled before the first reconciled day. Rerunning those earlier windows clears them.
  - A failed payout shows as `SETTLEMENT_MISMATCH` with actual `0`.
  - A pending payout shows as a failed run whose error says to rerun later; the next daily runs retry it.
  - An `UNMATCHED_ADJUSTMENT` is resolved after finance books the ledger adjustment.
- **Ledger meaning.** After a clean run the receivable is zero (LLD §16). Any residual is now either unmatched lines or unexplained PSP adjustments, each with an open exception.
