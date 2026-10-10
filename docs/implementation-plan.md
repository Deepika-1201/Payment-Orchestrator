# Implementation plan

V1, phases 1–17, is built; the [README roadmap](../README.md#roadmap) lists its scope. This plan closes V1 out, adds V2 (phases 18–28) from the [post-V1 requirements](requirements.md#8-post-v1-requirements-v2-draft), and defines how progress is measured. [ADR-034](decisions/ADR-034-post-v1-scope.md) records what V2 includes, what it leaves out, and why.

**Working agreement (V2):**

- Each phase first confirms the current RBI and NPCI rules its requirements cite. It then writes its section of [low-level-design.md](low-level-design.md) and its ADR, **before** any code.
- A phase is done when:
  - its exit criteria are met;
  - unit and integration tests are green in CI, and every rule the phase adds is caught by a test when deliberately broken;
  - `openapi.yaml`, the runbooks, the other docs and the ADRs reflect what changed;
  - the work is committed and pushed.

## Progress

| Scope | Planned units | Done | Progress |
|---|---|---|---|
| V1 (phases 1–17) | 23 | 22 | 96% |
| V2 (phases 18–28) | 15.5 | 7.5 | 48% |
| **Project** | **38.5** | **29.5** | **77%** |

*As of 2026-10-10.*

- **Unit:** the work of a typical V1 phase, such as phase 9 (routing engine). A phase's size includes its design, tests and docs.
- **Counting:** progress = completed units ÷ planned units. A phase counts only when it is done, with no partial credit. V1's close-out items count one by one.
- **Stable sizes:** the sizes are estimates fixed with this plan. A phase may be re-sized only when it starts, with an entry in the [changelog](#changelog). Moving work into or out of scope takes a new ADR.

## V1 baseline

| Phase | Scope | Units | Status |
|---|---|---|---|
| 1–5 | Discovery, requirements, HLD, LLD, ADRs | 3 | Done |
| 6 | Scaffolding: Gradle, Docker, CI, Flyway | 1 | Done |
| 7 | Payment domain, state machines, idempotency, payment API | 1.5 | Done |
| 8 | Provider SPI, mock PSPs, simulator | 1 | Done |
| 9 | Routing engine | 1 | Done |
| 11–12 | Webhooks, refunds, status resolver, expiry | 2 | Done |
| 10 | Razorpay and Cashfree adapters, settlement reports | 2 | Done except C1 |
| 13 | Reconciliation and shadow ledger | 2 | Done |
| — | API hardening | 1 | Done |
| — | Merchant management | 1 | Done |
| 14 | Risk and the review queue | 1 | Done |
| — | Operations and disputes | 1 | Done |
| — | Admin roles | 0.5 | Done |
| — | Per-merchant rate-limit overrides; UPI QR | 0.5 | Done |
| — | Security hardening (ADR-022 to ADR-026, ADR-033) | 2 | Done |
| 15 | Observability | 1 | Done |
| 16 | Terraform | 1 | Done except C2 |
| 17 | Load tests | 0.5 | Done except C3 |

### V1 close-out

These items belong to the phases above but need access the project does not have yet, so they count as not done.

| # | Item | Needs | Units | Exit criteria | Status |
|---|---|---|---|---|---|
| C1 | Razorpay and Cashfree sandbox runs | PSP test keys | 0.25 | The 6 sandbox contract tests, skipped without keys, pass | |
| C2 | Terraform applied; region failover drill | An AWS account | 0.5 | `terraform apply` to an account and the app healthy in Mumbai; the [region failover runbook](runbooks.md#region-failover) completed within the 30 min RTO (NFR-5); then `terraform destroy` | |
| C3 | Peak and spike load tests | C2 | 0.25 | The `peak` (1,000/s) and `spike` profiles pass their NFR-1/2 thresholds from a separate load generator | |

**Go-live gates.** These carry no units because they are outside engineering:
- an external penetration test;
- legal confirmation of the 8-year retention (NFR-16);
- for aggregator mode, an RBI Payment Aggregator authorization (FR-PA7).

## V2 phases

| # | Phase | Requirements | Depends on | Units | Exit criteria | Status |
|---|---|---|---|---|---|---|
| 18 | Recurring payments and mandates | [§8.1](requirements.md#81-recurring-payments-and-mandates-phase-18) | — | 3 | UPI AutoPay, card and eNACH mandates each register, notify and debit end to end on the mock PSP, and through one real adapter against its stub. A debit without an active mandate or a required notification, or above a limit, is refused (NFR-19). A revocation arriving by webhook stops the next debit | Done: [ADR-035](decisions/ADR-035-mandates.md), [LLD §18](low-level-design.md#18-recurring-payments-and-mandates-phase-18-adr-035), [steps](#phase-18-steps). The Razorpay sandbox check needs keys, as C1 does |
| 19 | Partial capture | [§8.2](requirements.md#82-partial-capture-phase-19) | — | 0.5 | Capturing less than the authorization releases the rest at the PSP; refunds, disputes and the ledger use the captured amount; capturing more is refused | Done: [ADR-036](decisions/ADR-036-partial-capture.md), [LLD §19](low-level-design.md#19-partial-capture-phase-19-adr-036). Only the mock PSPs capture partially: Razorpay requires the full amount, and the Cashfree adapter captures automatically |
| 20 | Wallets, EMI and pay later | [§8.3](requirements.md#83-wallets-emi-and-pay-later-phase-20) | — | 1.5 | Each method pays end to end on the mock PSP and through a real adapter's stub, and the hosted checkout offers it when routable. The phase changes nothing in `payment/domain` (NFR-11) | Done: [ADR-037](decisions/ADR-037-wallets-emi-pay-later.md), [LLD §20](low-level-design.md#20-wallets-emi-and-pay-later-phase-20-adr-037), [steps](#phase-20-steps). Razorpay offers them behind `extra-methods` once enabled on the account; its sandbox check needs keys, as C1 does |
| 21 | Bank transfers and virtual accounts | [§8.4](requirements.md#84-bank-transfers-and-virtual-accounts-phase-21) | — | 1.5 | An exact transfer pays the payment. Short, excess, repeated and unmatched credits follow the policy. Credits reconcile against settlement reports | Done: [ADR-038](decisions/ADR-038-bank-transfers.md), [LLD §21](low-level-design.md#21-bank-transfers-and-virtual-accounts-phase-21-adr-038), [steps](#phase-21-steps). Razorpay Smart Collect is behind `extra-methods`; its sandbox check needs keys, as C1 does |
| 22 | Dispute evidence | [§8.5](requirements.md#85-dispute-evidence-phase-22) | — | 1 | Evidence reaches the PSP (mock and a real adapter's stub) before the deadline. An approaching deadline raises the event and the alert. Accepting a dispute books the loss | Done: [ADR-039](decisions/ADR-039-dispute-evidence.md), [LLD §22](low-level-design.md#22-dispute-evidence-phase-22-adr-039), [steps](#phase-22-steps). Razorpay's Documents and Disputes APIs; its sandbox check needs keys, as C1 does. Cashfree merchants keep answering on its dashboard |
| 23 | International cards and multi-currency | [§8.6](requirements.md#86-international-cards-and-multi-currency-phase-23) | — | 1.5 | Payments in 0-, 2- and 3-decimal currencies are created, captured and refunded in minor units (NFR-20). The ledger balances in both currencies with the FX difference booked. Reconciliation matches in INR | |
| 24 | Cost-aware and adaptive routing | [§8.7](requirements.md#87-cost-aware-and-adaptive-routing-phase-24) | — | 1 | In simulated traffic, `COST` picks the cheapest healthy PSP, and `ADAPTIVE` converges on the better success rate without ever routing to an unhealthy PSP. Shadow mode changes no route | |
| 25 | Merchant billing | [§8.8](requirements.md#88-merchant-billing-phase-25) | — | 1 | A month's invoice equals the plan applied to the merchant's billable events, with GST. Issued invoices never change, corrections are credit notes, and numbers are sequential per financial year | |
| 26 | Aggregator mode I: onboarding | [§8.9](requirements.md#89-payment-aggregator-mode-phases-26-to-28) FR-PA1, FR-PA2 | — | 1 | A merchant reaches aggregator mode only with complete KYC checks and a second operator's approval. Expired KYC stops new payments in that mode | |
| 27 | Aggregator mode II: escrow and settlement | [§8.9](requirements.md#89-payment-aggregator-mode-phases-26-to-28) FR-PA3, FR-PA4, FR-PA7 | 25, 26 | 2 | Each settlement batch equals captures minus refunds, chargebacks, fees with GST and reserve changes. Property tests over random event sequences keep the ledger balanced and the escrow invariant (NFR-17) | |
| 28 | Aggregator mode III: payouts and escrow reconciliation | [§8.9](requirements.md#89-payment-aggregator-mode-phases-26-to-28) FR-PA5, FR-PA6 | 27 | 1.5 | Each payout is sent once despite retries, timeouts and restarts (NFR-18). A returned payout credits the merchant back and raises an exception. A planted difference in the escrow statement raises an exception the same day | |

**Not planned:** a merchant dashboard UI, a card vault, direct acquirer integrations, ML-based fraud and multi-region active-active. [ADR-034](decisions/ADR-034-post-v1-scope.md) gives the reasons and when to revisit each. The scale-driven changes in [architecture §11](architecture.md#11-evolution-path) wait for their triggers and are not counted.

**Order of work:** phases 23 to 28 in number order (27 needs 25 and 26; 28 needs 27). C1 to C3 follow whenever their access arrives. Left: 9 units, 8 in V2 and 1 in the close-out.

### Phase 18 steps

No partial credit: the phase's 3 units count when all nine steps are done. Steps 2 to 6 are done when step 7's tests pass.

| Step | Work | Status |
|---|---|---|
| 1 | Design: [ADR-035](decisions/ADR-035-mandates.md), [LLD §18](low-level-design.md#18-recurring-payments-and-mandates-phase-18-adr-035), [requirements §8.1](requirements.md#81-recurring-payments-and-mandates-phase-18) | Done |
| 2 | Schema (`V14__mandates.sql`), domain (`Mandate`, `MandateDebit`, mandate payments), repositories, provider SPI and `ProviderClient` calls | Done |
| 3 | `MandateService`, `MandateScheduler` and its worker jobs, mandate and notification webhooks, the merchant's `mandate_debit_limit` | Done |
| 4 | Mock PSP ([LLD §18.8](low-level-design.md#188-mock-psp)): mandates and notifications, the six SPI operations, mandate webhooks, simulator endpoints, capabilities of both mock providers | Done |
| 5 | `MandateController`: the seven endpoints of [LLD §18.2](low-level-design.md#182-merchant-api), POSTs with an `Idempotency-Key` | Done |
| 6 | `openapi.yaml`: paths and schemas, the six `mandate.*` events, `mandate_id` on payments, the three new error codes | Done |
| 7 | Tests: state machine unit tests; UPI AutoPay, card and eNACH end to end; limits; one debit in progress; revocation by webhook; retries; failed notifications; idempotency; database rules (NFR-19); mutation checks. Then commit and push on a green build | Done: 70 mutants, all killed (one first survived and got a stronger test) |
| 8 | Razorpay mandate adapter ([LLD §18.9](low-level-design.md#189-razorpay-mapping)): tests against its stub, and a sandbox test skipped without keys | Done |
| 9 | Docs: README API table, runbooks for any new alert, LLD notes on decisions made while coding, progress and changelog | Done (no new alerts) |

### Phase 20 steps

No partial credit: the phase's 1.5 units count when all steps are done.

| Step | Work | Status |
|---|---|---|
| 1 | Design: [ADR-037](decisions/ADR-037-wallets-emi-pay-later.md), [LLD §20](low-level-design.md#20-wallets-emi-and-pay-later-phase-20-adr-037), [requirements §8.3](requirements.md#83-wallets-emi-and-pay-later-phase-20) | Done |
| 2 | Shared model and capabilities (`MethodType`, `PaymentMethod.provider`, `CardDetails.emiPlan`, `MethodSupport` providers and tenures), routing by provider, schema V16, persistence | Done |
| 3 | Merchant API and `openapi.yaml`: the four methods on confirm, `method_provider` and `emi_plan` on attempts | Done |
| 4 | Mock PSP ([LLD §20.5](low-level-design.md#205-mock-psp)): capabilities, simulator pages and completion with a tenure, `emi_plan` in webhooks | Done |
| 5 | Hosted checkout ([LLD §20.4](low-level-design.md#204-hosted-checkout)): the new options with routable providers and tenures | Done |
| 6 | Razorpay ([LLD §20.6](low-level-design.md#206-razorpay-mapping)): `extra-methods`, single-instrument Payment Links, the plan from payments and webhooks; stub tests | Done |
| 7 | Tests: each method end to end on the mock PSP and through the Razorpay stub, routing by provider, checkout, contract, database rules; mutation checks; `payment/domain` unchanged. Then commit and push on a green build | Done: 29 mutants, 28 killed (one first survived and got a stronger test) and 1 equivalent: the Razorpay guard that skips a missing plan, which validation would refuse anyway |
| 8 | Docs: README, progress and changelog | Done |

### Phase 21 steps

No partial credit: the phase's 1.5 units count when all steps are done.

| Step | Work | Status |
|---|---|---|
| 1 | Design: [ADR-038](decisions/ADR-038-bank-transfers.md), [LLD §21](low-level-design.md#21-bank-transfers-and-virtual-accounts-phase-21-adr-038), [requirements §8.4](requirements.md#84-bank-transfers-and-virtual-accounts-phase-21) refined | Done |
| 2 | Model, SPI and schema V17: `BANK_TRANSFER`, bank details in the next action, provider credits, `transfer_credits`, refunds against credits, merchant settings, ledger types | Done |
| 3 | Allocation and expiry ([LLD §21.2](low-level-design.md#212-credits-and-allocation), [§21.3](low-level-design.md#213-waiting-and-expiry)): recording, allocation under the payment lock, returns, polling, accepting a short payment, closing the account | Done |
| 4 | Refunds, review, ledger and reconciliation ([§21.4](low-level-design.md#214-returns-and-merchant-refunds) to [§21.6](low-level-design.md#216-reconciliation)) | Done |
| 5 | Merchant API and `openapi.yaml`: confirm, next action, credits, refund fields; admin settings | Done |
| 6 | Mock PSP and simulator ([§21.8](low-level-design.md#218-mock-psp)) | Done |
| 7 | Razorpay Smart Collect ([§21.9](low-level-design.md#219-razorpay-mapping)) with stub tests | Done |
| 8 | Tests: exact, short (refund and accept), excess, exact-only, late, repeated and unmatched credits end to end; polling; returns that fail; merchant refunds; ledger; reconciliation; database rules; mutation checks. Then commit and push on a green build | Done: 52 mutants, 51 killed and 1 equivalent: posting a credit's receipt again when it is saved later, which the ledger ignores (one transaction per reference and type). Listing the mutants first removed six redundant checks and added twelve test cases |
| 9 | Docs: README, runbooks if an alert changes, progress and changelog | Done (no new alerts; the review-queue runbook covers the two new review reasons) |

### Phase 22 steps

No partial credit: the phase's unit counts when all steps are done.

| Step | Work | Status |
|---|---|---|
| 1 | Design: [ADR-039](decisions/ADR-039-dispute-evidence.md), [LLD §22](low-level-design.md#22-dispute-evidence-phase-22-adr-039), [requirements §8.5](requirements.md#85-dispute-evidence-phase-22) refined | Done |
| 2 | Schema V18 and domain: dispute responses, evidence files with per-file encryption, error codes | Done |
| 3 | Merchant API and `openapi.yaml`: evidence upload and listing, contest, accept, the dispute's `response`, two events; the upload path's body limit | Done |
| 4 | Delivery ([§22.3](low-level-design.md#223-responding-and-delivery)): recorded first, sent after commit, retried until the deadline, review and event on failure | Done |
| 5 | Deadline notice ([§22.4](low-level-design.md#224-deadline-notice-and-alert)): job, event, gauge, alert with runbook and promtool test, admin listing | Done |
| 6 | Provider SPI, mock PSP and simulator ([§22.5](low-level-design.md#225-provider-spi), [§22.6](low-level-design.md#226-mock-psp)) | Done |
| 7 | Razorpay documents, contest and accept ([§22.7](low-level-design.md#227-razorpay-mapping)) with stub tests | Done |
| 8 | Data key rotation covers file keys ([§22.2](low-level-design.md#222-evidence-storage)) | Done |
| 9 | Tests: contest and accept end to end on the mock PSP and the Razorpay stub; retries, refusals and the deadline; the notice; file checks; encryption at rest; contract; database rules; mutation checks. Then commit and push on a green build | Done: 55 mutants, 54 killed and 1 equivalent: the service's content-type check, which the request's own pattern makes unreachable, now removed. Listing the mutants first removed six redundant pieces of code and added seven test cases; the two surviving query mutants added one test case and extended another |
| 10 | Docs: README, runbooks, progress and changelog | Done (new alert `DisputeEvidenceDue` with its runbook; the review-queue runbook covers `response_failed`) |

### Phase 23 steps

No partial credit: the phase's 1.5 units count when all steps are done.

| Step | Work | Status |
|---|---|---|
| 1 | Design: [ADR-040](decisions/ADR-040-international-cards.md), [LLD §23](low-level-design.md#23-international-cards-and-multi-currency-phase-23-adr-040), [requirements §8.6](requirements.md#86-international-cards-and-multi-currency-phase-23) refined | Done |
| 2 | Schema V19, the merchant setting, foreign currencies in capabilities, creation and routing rules, amount steps for captures and refunds, INR-only amount risk | |
| 3 | Conversions ([§23.3](low-level-design.md#233-conversions)): SPI fields, recording under the payment lock, carried amounts, `conversion` on payments, refunds and disputes | |
| 4 | Ledger ([§23.4](low-level-design.md#234-ledger)): foreign movements against `fx_conversion`, conversion postings with `fx_gain_loss`, the per-currency balance check | |
| 5 | Reconciliation in INR ([§23.5](low-level-design.md#235-reconciliation)): `charged` on lines, recording from the report, `conversion_missing` | |
| 6 | Mock PSP and simulator ([§23.7](low-level-design.md#237-mock-psp)): currencies, rates, conversions in answers, webhooks and reports | |
| 7 | Razorpay ([§23.8](low-level-design.md#238-razorpay-mapping)): configured currencies, `base_amount`, recon lines in other currencies; stub tests | |
| 8 | Tests: payments in 0-, 2- and 3-decimal currencies created, captured and refunded end to end; rate moves with gains and losses; full refunds back to zero; chargebacks and reversals; reconciliation from the report; contract; database rules; mutation checks. Then commit and push on a green build | |
| 9 | Docs: README, progress and changelog | |

## Changelog

| Date | Change | Progress |
|---|---|---|
| 2026-10-06 | Plan drafted: V1 baseline, close-out C1–C3, V2 phases 18–28 ([ADR-034](decisions/ADR-034-post-v1-scope.md)) | 22 of 38.5 units (57%) |
| 2026-10-06 | ADR-034 accepted; phase 18 started with its design ([ADR-035](decisions/ADR-035-mandates.md)) | 22 of 38.5 units (57%) |
| 2026-10-07 | Phase 18 split into steps and the order of the remaining work recorded; [LLD §18.5](low-level-design.md#185-debit-cycle) gains the defaults for paused mandates and open circuits | 22 of 38.5 units (57%) |
| 2026-10-07 | Phase 18 done: mandates and debits on the mock PSP and the Razorpay adapter, NFR-19 enforced in the database. Decided while building ([LLD §18](low-level-design.md#18-recurring-payments-and-mandates-phase-18-adr-035)): the authorization window ends only registrations the customer has not acted on; a failed debit has its mandate checked at the PSP. Also fixed: merchants without a webhook URL could not change other settings | 25 of 38.5 units (65%) |
| 2026-10-07 | Phase 19 done: one partial capture per payment on PSPs that declare it per method, so far the mock PSPs (Razorpay requires the full amount; the Cashfree adapter captures automatically). Refunds, disputes, the ledger and reconciliation use the attempt's capture amount, which a database check keeps within the authorization. 36 mutants, all killed. Also fixed: a late authorization of another attempt during a capture moved the payment back to `authorized` instead of being voided | 25.5 of 38.5 units (66%) |
| 2026-10-08 | Phase 20 done: wallets, card EMI, cardless EMI and pay later on the mock PSPs and the Razorpay adapter, with no change to `payment/domain`. Routing matches the wallet or lender against what each PSP declares; the card EMI plan comes from the PSP with the card, set once. Decided while building ([LLD §20](low-level-design.md#20-wallets-emi-and-pay-later-phase-20-adr-037)): Razorpay EMI payments missing their plan in a listing are fetched again with `expand[]=card&expand[]=emi`, and an unreadable plan keeps the card. 29 mutants: 28 killed, 1 equivalent | 27 of 38.5 units (70%) |
| 2026-10-09 | Phase 21 done: bank transfers to a virtual account per attempt, on the mock PSP and Razorpay Smart Collect. Each credit is recorded once and allocated under the payment lock; excess, late and repeated credits go back as refunds against that credit; a payment still short at expiry is refunded or, if the merchant accepts short payments, captured for what arrived. Decided while building ([LLD §21.2](low-level-design.md#212-credits-and-allocation)): a credit in another currency goes back as late; an unplaceable credit on the platform-level webhook endpoint is logged and left to reconciliation, as no merchant can be named. 52 mutants: 51 killed, 1 equivalent | 28.5 of 38.5 units (74%) |
| 2026-10-10 | Phase 22 done: merchants contest or accept disputes through the API on PSPs that take answers that way (the mock PSPs and Razorpay; Cashfree merchants keep its dashboard). Evidence files are checked by their leading bytes and stored encrypted under a key per file, which data key rotation re-wraps. An answer is recorded first, delivered after commit and retried until the deadline; a refusal or the deadline fails it with a review item and an event. `dispute.evidence_due` goes out once, 3 days before the deadline, and a gauge with an alert covers disputes nobody answers. Decided while building ([LLD §22](low-level-design.md#22-dispute-evidence-phase-22-adr-039)): an answer counts as sent only when the PSP takes it or reports the status it leads to; delivery has no retry cap of its own, as the first attempt after the deadline fails it; refused uploads are not retried; files are listed on their own endpoint. 55 mutants: 54 killed, 1 equivalent | 29.5 of 38.5 units (77%) |
