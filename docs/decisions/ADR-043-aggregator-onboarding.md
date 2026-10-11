# ADR-043: Aggregator onboarding requires verified KYC and an independent approval

**Status:** Accepted (2026-10-11).

## Context

Phases 26 to 28 introduce funds handling. Existing merchant PSP accounts belong to merchants; aggregator payments instead use platform collection accounts. A mode switch must not redirect an older payment's refund or status check to a different account.

The RBI Payment Aggregators Directions, 2025 (15 September 2025), require merchant due diligence under the KYC directions, background checks, verified merchant settlement destinations, ongoing monitoring and separate escrow accounts. Sections 10, 13, 16 and 18 were checked against the RBI publication. Settlement timing is contractual; T+1 business day is this reference implementation's default, not a universal regulatory deadline. Software approval is not RBI authorization or independent verification of a PAN, GSTIN or bank account.

## Decision

- Merchants default to `ORCHESTRATOR`. Aggregator enrollment is an explicit maker-checker operation, never a settings toggle.
- A submission contains legal name, PAN, GSTIN, verified bank details, beneficial owners, risk category, merchant-wide transaction/exposure limits, and references to completed external KYC, background and bank verification evidence. Format checks do not substitute for those checks. Raw KYC and bank data are encrypted with the existing data-key ring; API views are masked and audit entries contain identifiers, not the raw documents.
- The submitter cannot approve their own submission. Approval requires complete evidence and a current validity period, then freezes the approved record. Low/medium/high risk refresh periods default to 730/365/180 days; these are conservative operational assumptions subject to deployment compliance review.
- Expired or unapproved KYC prevents new aggregator payment creation and confirmation, including scheduled debits. Existing captures, refunds, disputes, reconciliation and owed settlements remain recoverable; expiry must not strand customers' money.
- A merchant's collection-account owner is chosen once at first payment and cannot be changed afterwards. Conversion to aggregator mode therefore requires no existing payment or mandate history. Existing merchants can be migrated only by a separate reviewed migration, not by this API. An aggregator merchant cannot be converted back by a settings update.
- Platform collection accounts reuse the encrypted provider-account store under a configured platform merchant. Each aggregator merchant receives provider-account links with an immutable credential-owner account id, not copied credentials. Signed platform-account webhooks are accepted only for entities linked to that owner and provider; ordinary merchant-account webhooks retain their existing isolation.
- Aggregator processing is disabled by default. Enabling it requires an explicit platform merchant and, in production, a nonblank RBI authorization reference. The reference is a deployment declaration, not verification of authorization. The escrow/payout stages remain separately gated until their accounting is implemented.
- Risk limits apply per charge currency to the merchant's business, not per payment mode. Confirmation reserves a payment's daily volume once, in the same transaction as its attempt; retries do not consume it twice. Reservations are not released after failures within that day. Acquirer capabilities and existing issuer restrictions still apply.

## Alternatives

| Option | Trade-off |
|---|---|
| A mutable merchant mode flag | Simple, but silently changes historical account ownership and can misroute refunds |
| Copy platform secrets onto every merchant | Preserves the old resolver but duplicates credentials and complicates rotation and webhook scoping |
| Automatic approval from syntactically valid identifiers | Fast, but falsely treats format checks as legal due diligence |
| Disable all activity on KYC expiry | Easy, but prevents necessary refunds and recovery of existing funds |

## Consequences

- Onboarding is auditable, encrypted and independently approved, with runtime expiry enforcement.
- Existing orchestrator histories remain unaffected.
- Real verification services, merchant contracts, escrow banking, legal approval, RBI authorization and regulatory reporting remain deployment requirements.
- Phases 27 and 28 add authoritative accounting and payouts without weakening the onboarding and account-ownership rules.