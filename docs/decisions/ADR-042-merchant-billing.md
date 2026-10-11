# ADR-042: Merchant billing from immutable service events, with frozen monthly invoices

**Status:** Accepted (2026-10-11).

## Context

Phase 25 requires effective-dated merchant pricing, monthly invoices with an assumed 18% GST, financial-year numbering and corrections through credit notes (FR-B1 to FR-B3). PSP fee schedules from ADR-041 describe what a provider charges the merchant; they are not the gateway's own service prices.

PSP captures are not identical to billable successful payments: duplicate or rejected late captures can be automatically refunded. Foreign movements may become successful before their INR conversion is reported (ADR-040). Late status recovery can also arrive after an invoice has been issued.

## Decision

- **Separate billing module.** Gateway service charges live in `billing`, not the PSP shadow ledger. Payment application code publishes a small shared billing event in the same transaction as the successful business outcome. Billing never depends on payment persistence.
- **Prospective, versioned plans.** Finance publishes an immutable plan version per merchant with an effective timestamp, per-method payment charges (fixed INR paise plus basis points), optional refund charges and a monthly minimum in INR paise. Versions cannot be backdated. Missing method prices do not imply free service: they block issuance for affected events until finance supplies an explicit resolution. Merchants without a billing plan are not enrolled and are not charged.
- **Bill service outcomes once.** One payment charge belongs to the accepted successful payment, not every successful PSP attempt. Mandate authorization charges and automatic late/duplicate/credit-return refunds are excluded. An optional refund charge applies only to a successful merchant-requested refund. Stable event references prevent webhook, polling and reconciliation replays from adding a second charge.
- **Freeze the event's price basis.** Events retain the plan version selected when the gateway first records the billable outcome, the original amount/method, and the reported INR amount when known. For foreign payments and refunds, billing waits for the immutable conversion and uses that reported INR amount. It never computes an exchange rate.
- **Monthly INR invoices.** Periods are calendar months in `Asia/Kolkata`, issued in order starting with enrollment. An invoice can be issued only after its month closes. It includes all priced, uninvoiced events recorded before that month ends, including any prior-period events recovered after earlier issuance; late items retain their original service date. Unknown required INR amounts block issuance rather than silently omitting charges.
- **Monthly minimum policy.** The minimum is the plan effective at the end of the billing month, applied once for that month without proration. Event charges retain their own effective plan versions. The invoice adds only the shortfall needed to reach the monthly minimum, before GST. This policy also applies to an enrolled merchant with no billable activity.
- **Exact arithmetic and GST assumption.** Round each event's service charge half-up to INR paise; add minimum shortfall; calculate 18% GST on the invoice subtotal, rounded half-up. No floating-point money arithmetic is used. These are service-billing records, not a claim of production GST invoice compliance: supplier/recipient GST profiles, place-of-supply split, e-invoicing applicability and legal approval remain deployment requirements. Do not enable a plan that would violate applicable restrictions on payment-method charges, including UPI.
- **Immutable issue, explicit corrections.** Issuing atomically freezes the invoice, its lines, total and settlement collection mode. Repeated issue for a merchant/month returns the same invoice. Credit notes reference invoice lines, proportionally reverse their GST, and cannot cumulatively exceed a line's original taxable amount or tax. Repeated credit-note requests use a merchant-scoped reference and reject reuse with different content.
- **Transactional numbering.** Invoice and credit-note series use locked database counters by Indian financial-year start year (April to March). Numbers are unique and at most 16 characters, for example `INV/2026/000001` and `CN/2026/000001`. Counters, issued documents and event assignments commit together, so failed issuance consumes no number.
- **Collection mode.** In current orchestrator mode invoices are payable by the merchant. The invoice model retains a collection mode for settlement deduction, but enabling that mode and posting fees into merchant payable belongs to aggregator settlement in phase 27. This phase does not pretend to hold or deduct merchant funds.
- **Operations first.** Finance manages plans, issues closed-period invoices and credit notes, and inspects missing-price/conversion blockers through the admin API. An hourly idempotent billing job can issue the previous closed month for enrolled merchants; failures remain visible and retryable. No invoice emails, PDF renderer or merchant dashboard is added in this phase.

## Alternatives

| Option | Trade-off |
|---|---|
| Recompute invoices from mutable payment rows and current prices | Fewer tables, but replays, plan changes and late conversions change historical charges |
| Bill every successful PSP capture | Simple listener, but charges automatic reversals and duplicate captures as customer services |
| Estimate foreign amounts with a gateway FX feed | Produces early fees, but disagrees with the actual PSP conversion |
| Reopen an issued invoice after a late event | Avoids carry-forward items, but violates immutable issue and stable totals |
| Prorate or maximize the minimum across plan versions | Possible commercial policies, but more complex than an explicit end-of-month minimum; revisit when contracts need it |
| Deduct invoices from PSP shadow receivables now | Incorrect in orchestrator mode, where the gateway does not hold merchant funds |

## Consequences

- Billing prices and PSP prices remain distinct, independently auditable contracts.
- No enrolled event is silently lost to replay, late evidence, a missing price or a delayed foreign conversion.
- Financial documents and pricing history are append-only; corrections are linked credit notes.
- Invoice issuance serializes per merchant and financial-year counter. Payment transactions must not wait on a lock order that conflicts with issuance.
- Phase 27 will connect frozen service charges to aggregator settlement deduction without changing orchestrator invoices already issued.
- GST configuration and payment-method charging legality require deployment review; the assumed rate is not legal advice or regulatory certification.