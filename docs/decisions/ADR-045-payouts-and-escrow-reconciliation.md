# ADR-045: Send payouts once and reconcile escrow with bank evidence

**Status:** Accepted (2026-10-11).

## Context

Phase 27 reserves independently approved settlement batches in the authoritative book. Sending a bank transfer introduces a failure window between a durable local decision and the remote bank outcome. A timeout or process restart cannot justify sending another transfer, nor can a provider failure label alone prove that debited money returned.

RBI Payment Aggregators Directions, 2025, sections 16 and 18 require permitted escrow movements, sufficient day-end coverage and bank-registered settlement destinations. RazorpayX's published composite payout, idempotency, fetch and lifecycle contracts were checked for this phase: `X-Payout-Idempotency` is mandatory; the body must remain unchanged; payouts can be fetched by `reference_id`; processed payouts can reverse later; a failed current-account payout may become reversed only after money returns. Current-account payout fees may be debited separately from the principal transfer.

## Decision

- Add a payout SPI with a deterministic mock and a RazorpayX bank-account adapter. Payouts are INR only, initiated from the configured escrow source account to the independently approved and bank-registered destination frozen on the batch. The real adapter is disabled by default and must be explicitly configured; an ordinary current account is not automatically an authorized PA escrow account.
- One immutable payout instruction exists per approved, positive settlement batch. It stores a stable gateway payout id, provider, frozen amount, encrypted destination snapshot and a request fingerprint. The gateway id is also the provider idempotency key and reference. Zero-value reserve-only batches require no bank transfer.
- The worker commits `dispatch_started` before its single create request. Only the worker that changes that field from false to true may send. A crash after that commit may leave an instruction that never reached the provider; the trade-off is a visible unresolved reservation rather than a possible duplicate transfer.
- After dispatch starts, every retry and restart uses status lookup only. `NOT_FOUND` is not permission to create again. Unknown outcomes remain reserved, are checked with bounded backoff and raise an operational exception after the configured deadline. They are never silently expired or replaced by a new payout id for the same batch.
- Recheck holds, current bank-registration/destination version, eligible backing and cash before first dispatch. After dispatch starts, recovery is allowed even if configuration, KYC or merchant status changes. Existing instructions never pick up new destination details. A queued instruction can be cancelled before dispatch, releasing its batch reservation through a compensating entry; an in-flight one cannot be locally cancelled or redirected.
- Only confirmed success debits settlement payable and credits escrow. A definite pre-processing rejection releases settlement payable back to merchant payable. A confirmed reversal restores merchant payable and records the principal's return exactly once, even if the gateway first learns of the payout through its reversal. A provider `failed` state with uncertain cash return is review-required, not releasable.
- Signed payout webhooks and status checks enter the same monotone transition path. Events cannot change the frozen amount, currency, reference or destination. A success followed by a pending event stays successful; a return dominates stale success. Raw bank data and provider responses are not exposed in operational views or logs.
- RazorpayX uses the composite `POST /v1/payouts`, NEFT, `purpose=payout`, no low-balance queue and the immutable destination. Lookup uses provider id when known, otherwise source account plus gateway reference. Multiple matches, mismatched details, malformed responses and ambiguous HTTP outcomes stay unresolved. Production pins the API host, requires live credentials and refuses mock payouts.
- Bank statements are immutable normalized imports containing a bounded time window, opening/closing INR balance and uniquely identified signed entries. Reconciliation compares known receipt, payout and return references and the ledger cash balance, accounting only for explicitly identified known in-transit movements. An arbitrary adjustment amount cannot be used as a balancing plug.
- A bank-only fee or other unexplained movement creates an exception; it does not change a batch or infer a merchant charge. Finance can use independently approved, narrowly typed escrow adjustments to recognize verified platform bank fees, fee reversals or prefunding. The original statement and all correction evidence remain retained. Tax treatment and prefunding restrictions remain compliance responsibilities.
- Daily reconciliation and continuous debt/invariant checks expose critical alerts for unexplained escrow differences, missing statements, negative merchant balances and unresolved payouts. Returned payouts open an exception for finance before any new batch can reuse their restored payable. The worker never automatically re-pays a return.

## Alternatives

| Option | Trade-off |
|---|---|
| Retry creates after a timeout | Convenient, but risks duplicate money movement when remote deduplication expires or an integration drifts |
| Release every failed payout immediately | Restores availability quickly, but a failed status may precede the actual bank return |
| Read the merchant's latest destination on each retry | Simple, but changes the beneficiary of an already approved instruction |
| Reconcile only net bank balances | Can hide equal-and-opposite errors, duplicate transfers and payments to the wrong account |
| Automatically classify unexplained bank entries as fees | Clears dashboards, but manufactures accounting evidence and can hide missing funds |

## Consequences

- A transfer may require manual investigation after a crash before the only send; it cannot be automatically resent. This is an explicit at-most-once dispatch choice with durable visibility.
- Provider idempotency, immutable instructions, database uniqueness and status-only recovery defend the payout boundary independently.
- Bank statements, financial corrections and exception resolutions are retained and auditable; discrepancies are not erased to make a reconciliation pass.
- Real escrow onboarding, bank statement delivery, bank registration, payout credentials, IP allowlisting, provider sandbox certification, operational response, legal/tax review and RBI authorization remain external go-live gates.