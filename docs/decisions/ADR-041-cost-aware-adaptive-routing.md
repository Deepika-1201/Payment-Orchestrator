# ADR-041: Cost-aware routing and bounded adaptive exploration with shadow decisions

**Status:** Accepted (2026-10-10).

## Context

Phase 24 adds cost-aware selection, adaptive selection and shadow evaluation (FR-R5 to FR-R7). The routing engine already filters by merchant linkage, method capabilities and circuit availability before applying the first matching rule. Existing strategies are priority, weighted and provider-health ordering. Checkout and administrative previews also evaluate routes, so a read-only evaluation must not consume exploration capacity or create decision history.

PSP fee estimates must be merchant-account-specific and retain their effective dates: changing a price today must not change yesterday's settlement comparison. Foreign payments remain in their original minor units, with INR conversion known only after the PSP reports it (ADR-040).

## Decision

- **Keep the eligibility boundary.** Both new strategies rank only the linked, capable providers admitted by the existing circuit filter. They do not override an open circuit or a rule's target restrictions. Existing strategies remain unchanged.
- **Versioned fee schedules.** Operators publish append-only schedules per merchant PSP account, method and charge currency, with an effective timestamp. Each declares a fixed amount in that currency's minor units, a percentage in basis points, tax in basis points and whether fees are withheld or invoiced separately. Fee arithmetic uses decimal calculation and integer minor units; the base fee and its tax are rounded half-up separately.
- **Cost per successful payment.** `COST` orders candidates by their estimated fee, including tax, divided by predicted success probability. All estimates in one comparison use the payment's currency. Providers without a matching schedule come after priced providers; if none is priced, use existing health ordering and record the missing-price reason. Postpaid fees still affect cost even though no fee is withheld from settlement.
- **Bounded adaptation.** `ADAPTIVE` exploits the highest posterior mean on nine of every ten live evaluations; on the tenth it samples each eligible provider's Beta posterior and chooses the highest sample. This guarantees at most one exploration slot per ten live evaluations on each instance and rule. A sample that selects the current best provider does not increase exposure beyond that bound.
- **Observed outcomes.** Keep the most recent 100 qualifying final outcomes per provider and method, and per bank when known. Successes and issuer/provider failures inform prediction; customer abandonment, risk and request-validation failures do not. A bank-specific window is used after 20 observations, otherwise the method-level window applies. A Beta(19, 1) prior starts at 95% success and prevents zero-probability cost estimates. Use a tested distribution library for Beta sampling.
- **Separate prediction from health.** Existing provider-attributable health and circuit behavior stay intact. Including issuer outcomes in the routing prediction must not count them as technical circuit failures.
- **Shadow without live influence.** A rule may name an optional `COST` or `ADAPTIVE` shadow strategy. It evaluates the same eligible targets with separate sampling and exploration state. Persist the live and shadow choices together with the real attempt; shadow execution sends no PSP request and changes no live ordering. Preview calls are deterministic, read-only and consume neither live nor shadow exploration slots.
- **Check prices against settlements.** Compare the effective schedule at the reported capture time with the fee actually withheld on a matched capture. Postpaid schedules expect zero withholding. For foreign charges, calculate the percentage on the reported INR gross and allocate the fixed component using the reported INR/original gross amounts; round the fee and tax in settlement minor units. No new exchange rate is invented. A difference beyond one settlement minor unit raises a fee-schedule mismatch for finance, without changing the actual PSP fee or duplicating the money posting.
- **Operational history.** Keep bounded, queryable decision history for 90 days, without card or customer data. History records the attempt, merchant, method/bank, rule, live/shadow strategies and ordered candidates, selection reasons and the estimates used. Fee schedules remain immutable financial configuration history.

## Alternatives

| Option | Trade-off |
|---|---|
| Sample on every adaptive request | Standard Thompson sampling, but does not enforce the requested 10% exploration cap |
| Randomly explore with probability 0.1 | Simple average bound, but short bursts can exceed 10% |
| Global transactional exploration counter | Exact across instances, but adds a hot shared row to payment creation; per-instance prefix bounds add up across instances |
| Infer foreign exchange before routing | Produces comparable INR estimates but requires a new rate source and would differ from PSP settlement |
| Replace provider health with payment success probability | Conflates issuer/customer outcomes with provider technical health |
| Mutable fee rows | Easy administration, but historical reconciliation changes when a schedule is edited |
| Shadow through the normal live evaluator | Reuses code, but sampling and exploration counters can change the actual route |

## Consequences

- Operators can introduce new routing strategies in shadow mode before changing payment traffic.
- Estimates adapt per instance and reset on restart; cold starts use the explicit prior. Distributed model sharing is deferred until traffic demonstrates a need.
- The exploration bound applies to live routing evaluations, including an evaluation whose surrounding payment transaction subsequently rolls back, not to checkout previews.
- A fee mismatch is a configuration/reconciliation signal, not permission to replace the PSP's reported fee in the ledger.
- Historical schedule selection and foreign-fee allocation are tested alongside routing, circuit exclusion, preview purity and shadow isolation.
- Revisit shared prediction state and a global exploration budget when instance imbalance materially affects routing quality.