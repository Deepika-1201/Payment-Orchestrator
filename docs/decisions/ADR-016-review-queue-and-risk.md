# ADR-016: Manual review queue and risk decisions

**Status:** Accepted (2026-09-26)

## Context
Some outcomes are ones the gateway cannot decide on its own:
- a PSP reports a different amount than the attempt's;
- a PSP contradicts a final state, for example a refund reported `failed` after it `succeeded`;
- an attempt or refund stays unconfirmed for 72 hours of status checks;
- the risk engine returns `REVIEW` (FR-RK2).

Until now these set a bare `needs_review` boolean. It recorded neither why nor when, there was no way to list or acknowledge flagged items, and a risk `REVIEW` passed without a trace.

Three more gaps:
- **External fraud providers (FR-RK3) had no connector.** Rules also ran inside the confirm transaction, while the payment row was locked. A network call there would hold the lock and a pooled connection for the vendor's whole response time.
- **A failing rule (a bug, or a vendor outage) threw straight out of `confirm`.** The payment could not be confirmed at all.
- **Merchant webhooks don't cover these cases.** They report state changes; none of these flags changes state.

## Decision
- **Reasoned flags.**
  - A flag records its reasons (`amount_mismatch`, `provider_conflict`, `status_unresolved`, `risk_review`) and the time it was first flagged (`flagged_at`).
  - Reasons accumulate while the review is open.
  - Columns `review_reason` and `flagged_at` are added to `payment_attempts` and `refunds` (V7). Rows flagged before V7 get `unspecified`.
  - Partial indexes `WHERE needs_review` keep listing and counting cheap, because the open queue is tiny compared with the tables.
- **The risk decision is saved on every attempt** (`risk_outcome`, `risk_reasons`), including `ALLOW`, for audit.
  - Any outcome other than `ALLOW` that still proceeds (`REVIEW`, or `CHALLENGE` while 3DS is always on) flags the attempt `risk_review`.
  - On failover, the new attempt inherits the decision. A pure risk flag moves with it, so the queue shows the live attempt.
- **Resolving a review is an audited acknowledgement only.**
  - `POST /admin/v1/reviews/{attempts|refunds}/{id}/resolve {note}` clears the open flag under the payment row lock. It writes `review.resolved` to `audit_log` with the reasons and the note.
  - It never changes money state. Only provider evidence moves money: webhooks, status checks and reconciliation. Money corrections go through the normal APIs (refunds) or reconciliation.
  - Resolving twice returns `409`.
- **Queue:** `GET /admin/v1/reviews?kind=&merchant_id=&limit=` lists open items oldest first. The gauge `pg.reviews.open{kind}` drives alerting.
- **Risk runs before the row lock.**
  - `confirm` reads the payment without a lock and evaluates risk only if the payment looks confirmable.
  - It then locks and re-validates the payment, and acts on the decision.
  - The decision is based on the payment's amount, customer and method. Those do not change between the read and the lock.
- **A failing rule fails open to review.** The engine catches exceptions per rule and turns them into `REVIEW risk_rule_error` (metric `pg.risk.rule_errors{rule}`). A broken rule neither fails the payment nor silently allows it.
- **External connector (`ExternalRiskRule`), active only when `pg.risk.external.url` is set.**
  - **Request:** JSON (ids, amount, method, VPA, customer reference and email, IP, device id, recent attempt count), signed with `PG-Signature` using the merchant webhook scheme (HMAC-SHA256 with `pg.risk.external.secret`).
  - **Timeout:** bounded by `pg.risk.external.timeout` (800 ms by default). Redirects are not followed.
  - **Response:** `{decision: allow|review|challenge|block, reasons}`.
  - **Errors:** a timeout, a non-2xx response or an unreadable body gives `REVIEW external_risk_unavailable`. An unknown decision gives `REVIEW external_risk_invalid_response`.
  - **Vendor reasons:** they appear in failure messages and the queue. Only short codes matching `[a-z0-9_.:-]{1,64}` are kept (at most 5), prefixed `external:`.
  - **Metrics:** `pg.risk.external{result}` (timer).

## Alternatives
| Option | Trade-off |
|---|---|
| Separate `review_items` table | Full history per flag; duplicates state the aggregates already own, and must stay in sync with them. The audit log already keeps the history |
| Resolution that also "fixes" the payment (force succeed or fail) | Faster for operations; lets a human override provider evidence without an audit trail of the PSP's view. Corrections go through refunds and reconciliation instead |
| External risk inside the locked transaction | Simplest; holds a row lock and a connection for up to the vendor timeout on every confirm |
| Fail closed (block) when the vendor is down | Safer against fraud; a vendor outage becomes a full payment outage |
| Fail open silently (allow) | Best conversion; risky payments during an outage go unseen |
| **Fail open to review** | Payments continue and every unscored payment is visible; operations must work the queue during an outage |

## Consequences
- **Operations own the review queue.** An alert on `pg.reviews.open` is needed, and a runbook per reason:
  - `amount_mismatch`: contact the PSP; refund or adjust through reconciliation.
  - `status_unresolved`: chase the PSP; reconciliation heals the state from the settlement report.
  - `risk_review`: check the customer; refund if fraudulent.
- **Customer data goes to the fraud vendor** (email, IP, VPA, device). This needs a data-processing agreement and a matching privacy notice before `pg.risk.external.url` is set in production.
- **Confirm latency:** confirm waits up to `pg.risk.external.timeout`. The timeout is part of the confirm latency budget (NFR p99).
- **Stale velocity counts:** a concurrent confirm can see a velocity count that is one attempt old. This is acceptable for a soft signal.
- **Admin roles:** reviews are open to any admin token until admin roles exist.
