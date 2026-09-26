# ADR-024: Manual ledger adjustments with maker-checker

**Status:** Accepted (2026-09-26)

## Context
Reconciliation (ADR-012, ADR-017) finds money the automated postings cannot explain, for example:
- a short payout the PSP kept as an unreported fee;
- a rounding difference;
- a correction agreed with the PSP.

Until now the only options were to leave the residual on `PSP_RECEIVABLE` forever or to edit the database, which the append-only triggers rightly forbid. A manual posting moves money in the books, so one person must not be able to make it alone. Architecture §8 calls for maker-checker on manual money adjustments.

## Decision
- **Request:** `POST /admin/v1/ledger/adjustments` (permission `finance_write`) records a **pending** adjustment and posts nothing. It holds:
  - the merchant PSP account;
  - the debit and credit accounts (any two different ledger accounts);
  - the amount and currency;
  - a reason and an optional reference, such as a reconciliation exception.
- **Approval:** `POST …/{id}/approve {note}` posts the adjustment. The approver needs `finance_write` and must be a **different operator** than the requester.
  - The posting is an ordinary balanced ledger transaction (type `ADJUSTMENT`, reference `ADJUSTMENT:<id>`), so the ledger triggers and idempotency apply.
  - The four-eyes rule is enforced by the service, which returns `403`, and by a database check (`ck_adjustment_four_eyes`). A self-approval cannot be written even by a buggy caller.
- **Rejection:** `POST …/{id}/reject {note}` declines the request. The requester may use it to withdraw their own request.
- **Expiry:** pending requests expire after 7 days. They show as `expired` and can no longer be approved, so stale requests are re-validated before anyone approves them.
- **Audit:** every step is audited (`ledger_adjustment.requested`, `.approved`, `.rejected`) with the operator names from ADR-019 and ADR-023.
- **Corrections:** a wrong approved adjustment is corrected by another adjustment in the opposite direction. The ledger is never edited.

## Alternatives
| Option | Trade-off |
|---|---|
| Direct posting by `admin` | Fast; one compromised or mistaken account can move money in the books |
| Separate `approver` role | Explicit; the four-eyes property comes from two people, not two roles, and a separate role would still allow one person to hold both |
| Adjustments only as reconciliation exception resolutions | Tied to evidence; some corrections, such as PSP credit notes, have no exception |
| Free-form journal entries with more than two legs | Flexible; harder to review, and two-leg entries cover the known cases |

## Consequences
- **Finance workflow:** every write-off needs two named people. Approvals stay a deliberate, logged action.
- **Scope:** adjustments change only the shadow ledger. Money at the PSP is untouched, and refunds and disputes keep their own flows.
- **Admin roles:** the break-glass `admin` role holds `finance_write`, so it can act as the checker. Its use is visible in the audit log under `admin-token-<n>`.
