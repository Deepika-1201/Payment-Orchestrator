# ADR-037: Wallets, card EMI, cardless EMI and pay later as new method types

**Status:** Accepted (2026-10-07)

## Context
Phase 20 (requirements §8.3, FR-PM5 and FR-PM6) adds wallets, card EMI, cardless EMI and pay later. FR-PM4 and NFR-11 promised that a new method needs an adapter and capabilities, with no change to the payment domain. The exit criteria hold the phase to that promise. Facts that shaped the design, from Razorpay's documentation (2026-10-07):
- **Hosted pages do the work.** Razorpay's checkout offers wallets, EMI on credit and debit cards, cardless EMI and pay later. The customer enters the card, or signs in to the lender with an OTP, on Razorpay's page. A Payment Link can show a single instrument through its checkout `config` (a display block of `wallet` with `wallets`, `emi`, `cardless_emi` or `paylater` with `providers`, and `show_default_blocks: false`).
- **Providers are named instruments.** Wallets (`phonepe`, `amazonpay`, `mobikwik`, `payzapp`, …), cardless EMI lenders (`hdfc`, `icic`, `zestmoney`, `earlysalary`, `walnut369`, …) and pay-later providers (`lazypay`, …) each have a code. Lenders set their own minimum order amounts (₹900 to ₹7,000), and Razorpay enables cardless EMI and pay later per account on request.
- **The plan is chosen at the PSP.** For card EMI the customer picks the bank and the tenure on the PSP's page. The payment entity then reports the plan (`emi.duration`, `emi.rate`, `emi.issuer`, with `expand[]=emi`). Cardless EMI and pay-later payments report the provider; the repayment schedule stays between the customer and the lender.
- **Refunds stay normal.** Razorpay offers no instant refunds for EMI, cardless EMI or pay later. The gateway only issues normal refunds.

FR-PM5 cites no RBI or NPCI rule. The lender, a regulated entity, makes the credit decision and shows the borrower its loan terms on its own page. Wallet limits are the issuer's. The gateway sees neither the credit decision nor any credit data.

## Decision
- **Four method types:** `WALLET`, `EMI` (card EMI), `CARDLESS_EMI` and `PAY_LATER`. A confirm names the provider for wallets, cardless EMI and pay later (`wallet.provider`, …), and nothing more for card EMI. Card details never reach the gateway (ADR-008).
- **Capabilities per method.** Each method declares its amount range, its `providers` (wallets or lenders) and, for card EMI, the `tenures` offered (months). Routing only picks a PSP whose method lists the payment's provider. Tenures are shown on the hosted checkout and are not enforced: the PSP's page offers the real plans.
- **The plan is what the PSP reports.** Card EMI's tenure, interest rate (basis points) and issuer arrive with the card's network and last 4 digits, and are stored with them in the attempt's card details. For cardless EMI and pay later the gateway keeps the provider. The attempt API shows `method_provider` and `emi_plan`.
- **No change to the payment domain.** The method is a `PaymentMethod` and the plan travels in `CardDetails`, both in the shared model. The attempt state machine, outcomes, refunds, disputes, the ledger and reconciliation do not depend on the method. The phase commit leaves `payment/domain` untouched.
- **Mock PSP.** `MOCK_ALPHA` offers all four methods and `MOCK_BETA` some wallets, so routing by provider can be tested. Every new method redirects to the simulator page, where the customer picks a tenure for card EMI. The amount scenarios are the same as for other methods.
- **Razorpay.** The Payment Link shows only the chosen instrument. Status checks fetch EMI payments with their plan expanded, and webhooks are read for the plan when they carry it. The new methods are offered only for those listed in `pg.providers.razorpay.extra-methods`, which matches what Razorpay has enabled on the account.
- **Hosted checkout.** It offers each new method when at least one provider is routable for the payment. It lists the routable wallets and lenders, and the EMI tenures the routable PSPs offer.

## Alternatives

| Option | Trade-off |
|---|---|
| One generic method with a free-form instrument | No new types, but routing and validation could not tell a wallet from a loan |
| Customer picks the EMI plan on the gateway's checkout | The gateway would need the card to check eligibility, and a hosted Payment Link cannot preselect a tenure, so the customer would choose twice |
| Store the plan in a new attempt field | Clearer in the domain model, but it changes `payment/domain`, which this phase must not do. The plan belongs to the card payment, so `CardDetails` is a natural home |
| Restrict Razorpay's page with `options.checkout.method` | Only knows card, netbanking, UPI and wallet, and cannot name a provider |
| Model each lender's minimum amount | More precise routing, but adds a table of lender rules the PSPs already enforce on their pages. Each method declares one range |
| Cashfree for the new methods too | Its links filter by method group only (`link_meta.payment_methods`), not by provider. Razorpay alone meets the exit criteria |

## Consequences
- `MethodType` gains four values. `PaymentMethod` gains `provider`, `CardDetails` gains `emiPlan`, and `MethodSupport` gains `providers` and `tenures`.
- V16 extends the attempt's method type check and adds the plan columns, with a check that a plan comes with a card.
- `payment_method.type` accepts `wallet`, `emi`, `cardless_emi` and `pay_later`. Attempts show `method_provider` and `emi_plan`.
- Razorpay needs `pg.providers.razorpay.extra-methods` set once Razorpay has enabled the methods. Whether a plan arrives with webhooks, or only on status checks, is confirmed with sandbox keys (C1).
- Revisit when a PSP lets the gateway preselect a plan on a hosted page, when lender minimums cause failed attempts, or when Cashfree needs the new methods.
