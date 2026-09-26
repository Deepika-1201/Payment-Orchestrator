# ADR-020: Per-merchant rate-limit overrides

**Status:** Accepted (2026-09-26)

## Context
Every merchant gets the same per-instance token buckets (ADR-011): reads 200/s with a burst of 400, writes 100/s with a burst of 200. Merchants differ by orders of magnitude, though. A large merchant with a flash sale needs more headroom. A merchant under investigation, or with a buggy retry loop, needs less. Changing the global default for one merchant affects everyone, and a redeploy is too slow for a live incident.

## Decision
- **Override columns:** each merchant has optional read and write overrides (`per_second`, `burst`) in `merchants` (V10). A pair is either fully set or fully null, which is enforced by CHECK constraints. Null means the platform default (`pg.rate-limit`).
- **Admin API:**
  - `GET /admin/v1/merchants/{id}/rate-limits` returns the overrides together with the defaults they replace.
  - `PUT` sets them (permission `merchants_write`, audited as `merchant.rate_limits_updated`). A `PUT` without `read` or `write` returns that operation to the default.
  - Limits must satisfy 0 < per-second ≤ 100,000 and 1 ≤ burst ≤ 1,000,000.
- **Loading with authentication:** the overrides come from the API-key lookup the auth filter already makes on every request, as part of `MerchantPrincipal`. There is no extra query and no cache, so a change applies from the merchant's next request.
- **Buckets:** each bucket remembers its limit. When the effective limit changes, the filter replaces the bucket, which starts full at the new budget.

## Alternatives
| Option | Trade-off |
|---|---|
| Overrides in configuration | No schema change; a redeploy per change, and no audit trail |
| Tiers (`standard`, `high`, `enterprise`) | Fewer decisions for operations; every special case becomes a new tier |
| Separate cache of overrides with a TTL | Decouples from auth; stale for up to the TTL and another moving part, while the auth query is free to extend |
| **Nullable columns read with the API key** | Immediate, audited, no extra I/O; still per instance (ADR-011), so the effective limit is about the value × the instance count |

## Consequences
- **Budget changes:** raising or lowering a budget resets that merchant's bucket on each instance. It briefly allows a full burst at the new size.
- **Per-instance limits:** like the defaults, overrides are per instance. A shared limiter (Redis) remains the upgrade path if exact global limits are ever needed (ADR-011).
- **Suspending a merchant stays different from throttling one to near zero.** Suspension (ADR-014) is the tool for stopping a merchant.
