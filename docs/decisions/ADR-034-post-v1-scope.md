# ADR-034: Post-V1 scope, order and progress measure

**Status:** Accepted (2026-10-06)

## Context
V1, phases 1–17, is built and tested. What is left of it needs PSP sandbox keys or an AWS account. The requirements put many capabilities after V1:
- Payment Aggregator mode and more payment methods are "designed for" (§2 Q2, Q4).
- Recurring payments are "built after V1" (Q5).
- Cost-aware routing comes "later" (Q10).
- §7 lists more items as out of scope for V1.

None of them had a plan, a size or an order, so "how complete is the project?" had no answer. Estimates ranged from about 44% to 65%, depending on which deferred items counted.

## Decision
- **V2 scope, phases 18–28.** Requirements are in [requirements §8](../requirements.md#8-post-v1-requirements-v2-draft); phases, sizes and exit criteria are in the [implementation plan](../implementation-plan.md). The phases:
  - recurring payments and mandates;
  - partial capture;
  - wallets, EMI and pay later;
  - bank transfers and virtual accounts;
  - dispute evidence;
  - international cards and multi-currency;
  - cost-aware and adaptive routing;
  - merchant billing;
  - Payment Aggregator mode, in three phases: onboarding, then escrow and settlement, then payouts.
- **Not planned.** Each item has a trigger for revisiting it:

  | Item | Why not | Revisit when |
  |---|---|---|
  | Merchant dashboard or admin console UI | Q3 chose APIs as the product surface. A UI is a separate frontend project, outside this repository's focus on financial correctness | People without API tooling must run merchants or the platform |
  | Card vault in an isolated CDE (network tokens, BIN routing) | It brings PCI DSS Level 1 scope, and network token services need onboarding with the card networks. PSP-hosted fields and PSP tokens already cover saved cards at each PSP (ADR-008) | Saved cards must work across PSPs, or BIN routing saves enough to pay for the PCI scope |
  | Direct acquirer (ISO 8583) integrations | They need acquirer contracts and certification. The orchestrator works over PSP APIs (Q9) | Volume makes acquiring costs worth a direct connection |
  | ML-based fraud | There is no real traffic to train on, and external fraud providers already plug in (FR-RK3) | Production data exists |
  | Multi-region active-active | The warm standby is designed to meet NFR-3 to NFR-5. Two writers would give up the single-writer consistency of NFR-7 | The availability target rises above 99.95%, or one region can no longer carry the load |

- **Scale-driven changes** stay trigger-based in [architecture §11](../architecture.md#11-evolution-path) and are not counted: a broker, partitioning and sharding, Redis, and extracting services.
- **Order.**
  1. Recurring payments come first: Q5 promised them, and they reuse the payment machinery.
  2. Partial capture follows, as a small change to the payment lifecycle.
  3. Next come the new methods: wallets, EMI and pay later, then bank transfers, which add the matching of inbound credits.
  4. Dispute evidence and international cards follow.
  5. Routing comes after the methods, because it gains from them and from the PSP fee data in settlement reports.
  6. Merchant billing comes next, because aggregator settlement reuses its pricing.
  7. Payment Aggregator mode is last. It is the largest change and the only one that needs an RBI authorization to operate.
- **Progress measure.** One unit is the work of a typical V1 phase, such as phase 9 (routing). V1 is 23 units and V2 is 15.5, so the planned scope is 38.5. Progress = completed units ÷ 38.5.
  - A phase counts when it meets the plan's definition of done, with no partial credit. V1's close-out items count separately.
  - Sizes are fixed when the plan is accepted. They change only when a phase starts, with a changelog entry in the plan.

## Alternatives

| Option | Trade-off |
|---|---|
| Count phases, not units | Simpler, but partial capture (half a unit) would weigh as much as escrow and settlement (two) |
| Count requirements | Requirements vary in size even more than phases |
| Include everything §7 defers | The total would carry work nobody plans, so the figure would understate progress and never reach 100% |
| Measure V1 only | Nearly 100% today, and it says nothing about the product the requirements describe |
| Lines of code or commits | Rewards volume rather than finished, tested capability |

## Consequences
- Progress on 2026-10-06 is 22 of 38.5 units, 57%. V1 alone is 22 of 23 (96%); its close-out needs PSP keys and an AWS account.
- The figure moves only when a phase or a close-out item is done, or when a new ADR moves work into or out of scope.
- The V2 requirements are a draft. Each phase confirms the current RBI and NPCI rules it cites, and refines its requirements in its LLD section before any code.
- Aggregator mode is built as a reference implementation, like the rest. Operating it needs an RBI authorization, which the production guard enforces (FR-PA7).
