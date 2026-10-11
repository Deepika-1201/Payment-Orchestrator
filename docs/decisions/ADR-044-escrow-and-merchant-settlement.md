# ADR-044: Authoritative aggregator funds and immutable settlement batches

**Status:** Accepted (2026-10-11).

## Context

Phase 26 fixes collection ownership and approves the business merchant independently. Phase 27 must distinguish money owed by the PSP from cash already received in the platform's escrow, protect customer and merchant claims, and reserve each settlement once. The existing double-entry ledger is a shadow book for orchestrator merchants and must retain that behavior.

The RBI Payment Aggregators Directions, 2025, sections 16 and 18, were checked for this phase. Domestic escrow is an INR account with a scheduled commercial bank, used only for authorized PA business. Merchant settlement timing follows a fair, transparent agreement; T+1 business day is the project's default, not a universal RBI deadline. The bank must receive the merchant list before settlement. These requirements need bank contracts, operational controls and independent audits in addition to software.

## Decision

- Reuse the append-only double-entry ledger. Aggregator entries use the reserved `AGGREGATOR` book identifier in the existing provider dimension; the real provider remains on immutable treasury source records. Orchestrator postings keep their existing accounts and provider partitioning.
- Captures create `PSP_RECEIVABLE` and `MERCHANT_PAYABLE`, not escrow cash. Refunds and chargebacks reduce both; reversals restore them. Unapplied bank credits remain `CUSTOMER_FUNDS` until allocated or returned. Foreign source amounts retain a balanced nominal-currency book; INR payable is recognized only from the reported conversion. Actual PSP conversion differences and withheld PSP fees are passed through to the merchant under this reference contract, not invented or absorbed implicitly.
- Physical `ESCROW` is credited only by a completed, fully matched PSP settlement, allocated across its business merchants. The full platform-account report is reconciled once; aliases do not independently import the same report. Unknown allocations or a mismatch with the PSP payout leave an exception and do not manufacture available cash.
- The INR book invariant is `ESCROW + PSP_RECEIVABLE = MERCHANT_PAYABLE + RESERVE + SETTLEMENT_PAYABLE + CUSTOMER_FUNDS + PLATFORM_FEE_INCOME + GST_PAYABLE`. Receivables, including negative receivables for unsettled refunds, are explicit in-transit items, not bank cash. Phase 28 additionally compares actual escrow with the bank statement and tracks payout outcomes.
- Gateway fees and GST enter the authoritative book in the same transaction as invoice issuance. Aggregator invoices use `SETTLEMENT_DEDUCTION`; their exact frozen subtotal and GST reduce merchant payable once. Credit notes reverse the original invoice's collection treatment. There is no provisional per-payment GST calculation or second charge when the invoice is read. Fees are recovered from settlements after monthly issuance; the contract and reserve policy must cover that credit exposure.
- A merchant has an audited settlement policy: business-day delay (default one), a fixed INR reserve target (default zero), an explicit review hold, and evidence that the bank has registered its currently approved destination. Weekends and configured bank holidays are excluded. Production must supply its reviewed bank calendar and registration evidence.
- Settlement sources are immutable and trace each capture, refund, chargeback, fee, credit and reserve movement. Positive receipts mature on their contractual business day; debits reduce eligibility immediately. A draft freezes its source lines and exact net amount, reserves that amount from merchant payable into settlement payable, and requires a different finance operator's approval. Draft rejection releases the reservation with a compensating posting; approved amounts, destination version, policy and source lines never change.
- A pending draft cannot be approved after a new debit makes its eligible backing insufficient. Suspended merchants, explicit holds and unresolved payment/reconciliation review items prevent release. KYC expiry alone does not confiscate an already-earned settlement, but a replaced destination needs a newly registered destination and a new batch.
- No batch reserves more than available physical escrow after existing commitments and protected balances. Cash allocation and book mutations share a database lock for the single escrow account in this reference deployment; correctness takes priority over speculative throughput. This serialization must be measured under C3 before production sizing.
- Negative merchant payable is retained, offset against its own reserve where possible, then recovered from later receipts. It is never silently written off or charged to another merchant. Finance visibility and the critical escrow/debt alerts are completed in phase 28.
- Collection-owner accounts are dedicated to platform collection, not business payments. Activation must not repurpose an owner's existing payment or mandate history. A bank-account or escrow-bank migration is a separate reviewed operation, not a configuration toggle.

## Alternatives

| Option | Trade-off |
|---|---|
| Treat every capture as cash in escrow | Easy batch arithmetic, but releases money before the PSP has paid it |
| Recompute payouts directly from mutable payment tables | Fewer records, but loses a reproducible financial source set and makes replay unsafe |
| Deduct provisional GST on each payment | Earlier fee recovery, but creates rounding and month-close reconciliation complexity; not needed by the monthly billing contract |
| Reuse a merchant's personal PSP account as the collection owner | Avoids a new account, but mixes legacy merchant money with pooled funds |
| Automatically release every positive balance | Removes operator work, but bypasses review, cash availability and bank-registration controls |

## Consequences

- Aggregator accounting becomes a book of record while orchestrator behavior remains unchanged.
- Batches explain their arithmetic through immutable sources and survive retries without double reservation.
- Late reports, refunds, invoice fees and chargebacks remain visible rather than reopening historical batches.
- Default reserve size, settlement delay, fee pass-through, holiday calendar, bank registration and monthly credit exposure require contractual and compliance approval before real funds are handled.
- Phase 28 supplies the payout SPI, unknown-outcome recovery, returned payouts, bank-statement matching and operational alerts. Software does not establish an escrow account or certify authorization.