# ADR-012: Orchestrator money flow with a shadow double-entry ledger

**Status:** Accepted (2026-09-26)

## Context
In orchestrator mode the platform never holds funds: PSPs settle directly to merchants. Finance still needs to answer: what does each PSP owe each merchant, did settlements arrive, and where did fees, refunds, and chargebacks go? Payment state ("succeeded") is not financial state ("money settled, net of fees").

## Decision
- Keep payment state (state machines) and financial state (ledger) **separate**.
- Add an immutable double-entry **shadow ledger** (Phase 13). Accounts are per merchant PSP account: `PSP_RECEIVABLE`, `SALES_CLEARING`, `PSP_FEES`, `REFUNDS`, `CHARGEBACKS`, `BANK_SETTLEMENTS`.
- Postings are made by in-process listeners in the same transaction as the state change, and are idempotent via `UNIQUE(reference_type, reference_id, entry_type)`:

| Event | Debit | Credit |
|---|---|---|
| Payment captured ₹X | PSP_RECEIVABLE | SALES_CLEARING |
| Refund succeeded ₹R | REFUNDS | PSP_RECEIVABLE |
| PSP fee ₹F (from report) | PSP_FEES | PSP_RECEIVABLE |
| Chargeback ₹C | CHARGEBACKS | PSP_RECEIVABLE |
| Settlement received ₹S | BANK_SETTLEMENTS | PSP_RECEIVABLE |

- Each transaction is enforced balanced by a deferred constraint trigger. `UPDATE`/`DELETE` are forbidden; corrections are made with reversal transactions.

## Alternatives
| Option | Trade-off |
|---|---|
| No ledger (derive from payments) | Simple; cannot represent fees, settlements, or chargebacks consistently; reconciliation becomes ad-hoc SQL |
| **Shadow double-entry ledger** | Standard accounting guarantees; `PSP_RECEIVABLE` should net to zero after settlement, and any residual is a reconciliation exception |
| Full PA ledger with merchant balances and payouts | Needed only in Payment Aggregator mode (RBI authorization, escrow) |

## Consequences
- Moving to PA mode later adds accounts and flows (escrow, merchant payable, payouts) without redesign.
- Ledger balances are computed from entries (with periodic balance snapshots at scale).
