# ADR-040: International cards: the PSP converts, the gateway records the INR it reports

**Status:** Accepted (2026-10-10).

## Context
Phase 23 (requirements §8.6, FR-FX1 to FR-FX3, NFR-20) lets merchants charge in currencies other than INR. V1 already stores ISO 4217 codes and integer minor units everywhere, but accepts INR only, and the ledger's balance check adds debits and credits across currencies.

Facts from Razorpay's documentation on international payments, currency conversion, payments, refunds, disputes and settlements (2026-10-10):
- **Currencies.** About 100, in each currency's smallest unit with the ISO 4217 exponent: 0 decimals (JPY, KRW, VND, CLP, …), 3 (BHD, IQD, JOD, KWD, OMR, TND), 2 for the rest. A 3-decimal amount must end in 0 (99.990 KWD, not 99.991), for payments and refunds.
- **Who pays.** Foreign-currency payments need international cards, and the merchant's Razorpay account must have international payments activated.
- **Conversion.** Razorpay settles in INR, converting at the processing bank's rate on the payment date. A non-INR payment carries `base_amount` (paise) and `base_currency: INR`, without a rate. Refunds and disputes document no INR amount; their INR effect shows only in the settlement report (`credit`, `debit`).
- Cashfree also offers international cards with INR settlement; its API for the INR amounts was not reviewed.

Rules checked:
- RBI's Master Direction on Regulation of Payment Aggregators (15 September 2025, replacing the 2023 cross-border circular): a card transaction where the card network settles the foreign exchange and the aggregator receives local currency is not cross-border aggregation (PA-CB). The PA-CB cap of ₹25 lakh per transaction therefore does not apply to these payments. Settlement in a currency other than INR is allowed only for exporters onboarded directly by a PA-CB; refunds go to the original payment method.
- RBI's 2025 authentication directions add a factor for cross-border card-not-present payments made with Indian cards abroad (from 1 October 2026). Foreign cards used here authenticate under their own issuers' rules, at the PSP (3-D Secure 2).
- UPI (NPCI), netbanking, wallets, EMI, bank transfers and e-mandates are INR systems.

## Decision
- **Opt-in, cards only.** An operator enables `international_cards` per merchant; it is off by default. Only then can the merchant create payments in another currency, and those payments can only be paid by card. Mandates and bank transfers stay INR.
- **Currencies come from the PSPs.** A PSP's capabilities list the foreign currencies it charges cards in, each with a smallest and largest amount and an amount step (10 for Razorpay's 3-decimal currencies). Creating a payment in a currency none of the merchant's PSPs takes answers 422 `unsupported_currency`; routing skips a PSP whose list excludes the currency or the amount. Amounts are integers in the currency's ISO 4217 minor unit. The gateway's ₹1 to ₹10,00,000 limits and the amount risk thresholds are in rupees and apply to INR; a payment in another currency is capped by its PSPs' largest amount.
- **The PSP converts, the gateway records.** For each capture, refund, chargeback and chargeback reversal of a foreign payment, the gateway stores the INR amount the PSP reports and, when the PSP gives one, its rate, as the exact decimal it was given. Nothing is computed in floating point and no rate is ever applied by the gateway.
  - A capture's INR amount comes with the PSP's answer (Razorpay's `base_amount`), else from the settlement report's line, which also covers captures healed by reconciliation.
  - A refund's INR amount comes with the PSP's answer when it has one, else from the settlement report. A chargeback's and its reversal's come from the settlement report.
  - Payments, refunds and disputes show it as `conversion` once known.
- **The ledger keeps both currencies.** Sales, refunds and chargebacks post in the currency charged, against an `fx_conversion` account. Their INR amounts post between `fx_conversion` and the PSP receivable, which stays in INR so that it reconciles with the INR payouts.
  - A refund or chargeback takes back the INR the capture booked for that part of the payment. The difference from the INR the PSP actually took goes to `fx_gain_loss`.
  - The part is allocated on the running total, rounded half-even to the paisa, so a fully refunded payment gives back exactly the INR captured.
  - The database rejects any ledger transaction that does not balance in each of its currencies.
- **Reconciliation in INR.** A report line for a foreign item matches when its INR amount equals the one recorded. If none is recorded yet, the line's amount is recorded and the item matches. A different amount is an amount mismatch for review. A refund or chargeback that cannot be valued because its capture's INR amount is unknown is flagged for finance.
- **Refunds** are made in the payment's currency, within its captured amount (FR-FX3), and in the PSP's amount steps.
- **PSPs.** The mock PSP converts at rates the simulator sets, so a test can move the rate between a capture and its refund; one scenario reports INR amounts only in the settlement report, as Razorpay does for refunds. Razorpay declares the currencies the deployment configures (none by default) and reads `base_amount`. Cashfree stays INR only.

## Alternatives

| Option | Trade-off |
|---|---|
| The gateway converts with its own rate feed | INR amounts known early, but they would never match what the PSP settles, and NFR-20 rules out computing them |
| Ledger in INR only, foreign amounts as notes | Simplest, but the books would not show what was sold and refunded in the currency charged, and FX differences would disappear into sales and refunds |
| Book the FX difference only once a payment is fully refunded or charged back | Exact without allocation, but a partly refunded payment would never show its difference |
| Value refunds at a reported rate | Needs a rate with every event; Razorpay gives none |
| A currency allow-list per merchant | Finer control, but the PSPs and the merchant's PSP accounts already limit currencies, and one switch keeps onboarding simple |
| Amount risk thresholds per currency | Finer risk control, but each currency needs its own thresholds to keep up to date; the PSP's per-currency maximum bounds the exposure for now |
| Dynamic currency conversion (the customer pays in their own currency, the merchant charges INR) | A PSP checkout feature (Razorpay, Cashfree's Pay Native). The merchant still charges INR, so nothing changes in the gateway |

## Consequences
- Merchants can sell abroad in the buyer's currency on PSPs that support it, and see in each payment, refund and dispute what the PSP converted, once it is known.
- A refund's INR amount may stay unknown until its settlement report, a few days on Razorpay. Until then the ledger holds the refund in the currency charged only, and the PSP receivable does not yet include it.
- Finance sees FX gains and losses per merchant PSP account in `fx_gain_loss`, and the INR value of foreign sales not yet refunded or charged back in `fx_conversion`.
- Every ledger transaction is checked per currency, a stricter rule for all postings, INR ones included.
- Whether Razorpay's settlement report gives foreign lines in INR as assumed is confirmed with sandbox keys (C1).
- Revisit per-currency risk thresholds when foreign volume grows.
