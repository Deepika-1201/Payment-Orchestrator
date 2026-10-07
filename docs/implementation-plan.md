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
| V2 (phases 18–28) | 15.5 | 3 | 19% |
| **Project** | **38.5** | **25** | **65%** |

*As of 2026-10-07.*

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
| 19 | Partial capture | [§8.2](requirements.md#82-partial-capture-phase-19) | — | 0.5 | Capturing less than the authorization releases the rest at the PSP; refunds, disputes and the ledger use the captured amount; capturing more is refused | |
| 20 | Wallets, EMI and pay later | [§8.3](requirements.md#83-wallets-emi-and-pay-later-phase-20) | — | 1.5 | Each method pays end to end on the mock PSP and through a real adapter's stub, and the hosted checkout offers it when routable. The phase changes nothing in `payment/domain` (NFR-11) | |
| 21 | Bank transfers and virtual accounts | [§8.4](requirements.md#84-bank-transfers-and-virtual-accounts-phase-21) | — | 1.5 | An exact transfer pays the payment. Short, excess, repeated and unmatched credits follow the policy. Credits reconcile against settlement reports | |
| 22 | Dispute evidence | [§8.5](requirements.md#85-dispute-evidence-phase-22) | — | 1 | Evidence reaches the PSP (mock and a real adapter's stub) before the deadline. An approaching deadline raises the event and the alert. Accepting a dispute books the loss | |
| 23 | International cards and multi-currency | [§8.6](requirements.md#86-international-cards-and-multi-currency-phase-23) | — | 1.5 | Payments in 0-, 2- and 3-decimal currencies are created, captured and refunded in minor units (NFR-20). The ledger balances in both currencies with the FX difference booked. Reconciliation matches in INR | |
| 24 | Cost-aware and adaptive routing | [§8.7](requirements.md#87-cost-aware-and-adaptive-routing-phase-24) | — | 1 | In simulated traffic, `COST` picks the cheapest healthy PSP, and `ADAPTIVE` converges on the better success rate without ever routing to an unhealthy PSP. Shadow mode changes no route | |
| 25 | Merchant billing | [§8.8](requirements.md#88-merchant-billing-phase-25) | — | 1 | A month's invoice equals the plan applied to the merchant's billable events, with GST. Issued invoices never change, corrections are credit notes, and numbers are sequential per financial year | |
| 26 | Aggregator mode I: onboarding | [§8.9](requirements.md#89-payment-aggregator-mode-phases-26-to-28) FR-PA1, FR-PA2 | — | 1 | A merchant reaches aggregator mode only with complete KYC checks and a second operator's approval. Expired KYC stops new payments in that mode | |
| 27 | Aggregator mode II: escrow and settlement | [§8.9](requirements.md#89-payment-aggregator-mode-phases-26-to-28) FR-PA3, FR-PA4, FR-PA7 | 25, 26 | 2 | Each settlement batch equals captures minus refunds, chargebacks, fees with GST and reserve changes. Property tests over random event sequences keep the ledger balanced and the escrow invariant (NFR-17) | |
| 28 | Aggregator mode III: payouts and escrow reconciliation | [§8.9](requirements.md#89-payment-aggregator-mode-phases-26-to-28) FR-PA5, FR-PA6 | 27 | 1.5 | Each payout is sent once despite retries, timeouts and restarts (NFR-18). A returned payout credits the merchant back and raises an exception. A planted difference in the escrow statement raises an exception the same day | |

**Not planned:** a merchant dashboard UI, a card vault, direct acquirer integrations, ML-based fraud and multi-region active-active. [ADR-034](decisions/ADR-034-post-v1-scope.md) gives the reasons and when to revisit each. The scale-driven changes in [architecture §11](architecture.md#11-evolution-path) wait for their triggers and are not counted.

**Order of work:** phases 19 to 28 in number order (27 needs 25 and 26; 28 needs 27). C1 to C3 follow whenever their access arrives. Left: 13.5 units, 12.5 in V2 and 1 in the close-out.

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

## Changelog

| Date | Change | Progress |
|---|---|---|
| 2026-10-06 | Plan drafted: V1 baseline, close-out C1–C3, V2 phases 18–28 ([ADR-034](decisions/ADR-034-post-v1-scope.md)) | 22 of 38.5 units (57%) |
| 2026-10-06 | ADR-034 accepted; phase 18 started with its design ([ADR-035](decisions/ADR-035-mandates.md)) | 22 of 38.5 units (57%) |
| 2026-10-07 | Phase 18 split into steps and the order of the remaining work recorded; [LLD §18.5](low-level-design.md#185-debit-cycle) gains the defaults for paused mandates and open circuits | 22 of 38.5 units (57%) |
| 2026-10-07 | Phase 18 done: mandates and debits on the mock PSP and the Razorpay adapter, NFR-19 enforced in the database. Decided while building ([LLD §18](low-level-design.md#18-recurring-payments-and-mandates-phase-18-adr-035)): the authorization window ends only registrations the customer has not acted on; a failed debit has its mandate checked at the PSP. Also fixed: merchants without a webhook URL could not change other settings | 25 of 38.5 units (65%) |
