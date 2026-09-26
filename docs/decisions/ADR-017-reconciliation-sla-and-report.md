# ADR-017: Reconciliation exception ownership, SLA and daily report

**Status:** Accepted (2026-09-26)

## Context
Reconciliation (ADR-012) opens deduplicated exceptions and auto-heals what is safe. Operations still lacked three things to run it day to day:
- **Ownership:** it was unclear who is working an exception.
- **Deadline:** nothing said when an exception becomes late. A missing capture or a short payout left for a week is a finance incident.
- **Daily view:** there was no summary of the previous business day. Did every account reconcile? What broke? How big is the backlog?

The daily job also logged failures per account and moved on. An account whose run failed, or never started, showed nowhere.

## Decision
- **Every exception gets a due date when it is opened:**
  - `due_at = created_at + pg.reconciliation.exception-sla` (48 h by default);
  - existing rows are backfilled with 48 h (V8).
- **One SLA for all exception types.** Per-type SLAs can be added when finance asks for them. The schema stores `due_at`, so changing the rule later needs no migration.
- **Ownership:**
  - `POST /admin/v1/reconciliation/exceptions/{id}/assign {assignee}` sets `assignee` and `assigned_at` on an open exception. Reassigning is allowed.
  - Each assignment is audited (`reconciliation_exception.assigned`, with the previous assignee).
  - `assignee` is a free-text operator id until admin roles and SSO exist.
- **Overdue** means open and past `due_at`:
  - it is computed at read time, so no job flips a flag;
  - the queue filters with `?overdue=true` and `?assignee=`, and each item shows `overdue`;
  - gauges `pg.reconciliation.exceptions.open` and `.overdue` drive alerts;
  - a partial index `WHERE status = 'OPEN'` on `due_at` keeps them cheap.
- **Daily report:** `GET /admin/v1/reconciliation/reports/daily?date=&merchant_id=`. `date` is a local date in `pg.reconciliation.zone` (Asia/Kolkata), the same zone the 02:30 job uses.
  - **Accounts:** every account the job was due to reconcile, with the result of its latest run for exactly that day's window (reruns replace earlier results). An account is `missing` when no run exists.
  - **Exceptions:** those opened by that day's runs, by type, and how many are now resolved, open or overdue.
  - **Backlog:** all open and overdue exceptions from any day.

## Alternatives
| Option | Trade-off |
|---|---|
| Per-type SLA from day one | Closer to finance policy; more configuration before anyone has asked for it |
| `overdue` flag updated by a job | Simple queries; stale between runs, and one more job to monitor |
| Report computed and stored nightly | Fast reads and a frozen snapshot; a second copy of the data that drifts after reruns and resolutions |
| **Report computed on request from runs and exceptions** | Always current, no new job; slightly more expensive reads, which is fine for an admin endpoint |
| Assignment in a ticketing tool (Jira) | Richer workflow; the gateway could not report ownership, and the audit trail would be split |

## Consequences
- **Audit:** operators should assign exceptions as they pick them up. Unassigned overdue exceptions are the alert that matters.
- **Account list:** the report's account list is computed as of today. An account removed since that day no longer appears as `missing`, but its runs still appear.
- **Zone changes:** changing `pg.reconciliation.zone` moves day boundaries. Windows before and after the change can overlap or leave gaps, so it needs a planned cut-over.
- **Admin roles:** reports and assignment are open to any admin token until admin roles exist.
