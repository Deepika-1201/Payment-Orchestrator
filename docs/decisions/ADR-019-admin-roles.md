# ADR-019: Admin roles with hashed operator tokens and deny-by-default permissions

**Status:** Accepted (2026-09-26)

## Context
The admin API grew to cover several kinds of work:
- merchant onboarding and PSP credentials;
- routing;
- webhook replay;
- manual reviews;
- reconciliation;
- ledger reads.

Every call used one of a few static tokens. Each token could do everything, and the audit log recorded only `admin-token-<n>`, which did not say who acted. Architecture §8 names the target: OIDC SSO with the roles `ADMIN`, `OPS`, `FINANCE` and `READ_ONLY`. Until an identity provider is chosen and wired in, the gateway still needs:
- **least privilege**: a finance analyst must not be able to rotate PSP credentials;
- **named actors** in the audit log;
- **tokens that do not appear in plaintext in configuration**.

## Decision
- **Named operators** are configured under `pg.security.admin-users`:
  - each has a `name` (the audit actor), `token-sha256` (hex SHA-256 of a high-entropy bearer token) and `roles`;
  - configuration and secrets never hold a usable token;
  - the filter hashes the presented token and compares it in constant time with every entry;
  - names and hashes must be unique, which is validated at startup.
- **Permissions** (`AdminPermission`), granted by role:

  | Permission | Covers | ADMIN | OPS | FINANCE | READ_ONLY |
  |---|---|---|---|---|---|
  | `READ` | every admin `GET` (secrets are always masked) | ✓ | ✓ | ✓ | ✓ |
  | `MERCHANTS_WRITE` | create or update merchants, API keys, webhook secrets, PSP accounts and credentials | ✓ | | | |
  | `MERCHANTS_SUSPEND` | suspend or reactivate a merchant | ✓ | ✓ | | |
  | `ROUTING_WRITE` | create or change routing rules | ✓ | | | |
  | `OPERATIONS_WRITE` | webhook replay, acknowledging manual reviews | ✓ | ✓ | | |
  | `FINANCE_WRITE` | reconciliation runs, assigning and resolving exceptions | ✓ | | ✓ | |

- **Enforcement is deny by default:**
  - every admin handler declares `@RequiresAdmin(permission)`, and `GET` defaults to `READ`;
  - an interceptor refuses any other admin endpoint without the annotation (`403 forbidden`, logged, metric `pg.admin.denied{permission}`);
  - a test walks all admin mappings and fails the build if a write endpoint is undeclared.
- **Break-glass tokens:**
  - `pg.security.admin-tokens` (plaintext, role `ADMIN`, actor `admin-token-<n>`) remains for local development, tests and emergencies;
  - a warning is logged at startup whenever they are configured.
- **OIDC later:** an OIDC filter will produce the same `AdminPrincipal` (name and roles from token claims), so permissions and annotations do not change.

## Alternatives
| Option | Trade-off |
|---|---|
| Spring Security with method security | Standard and feature-rich; a large dependency and configuration surface for bearer-token checks and one interceptor. Worth adopting together with OIDC |
| Path-pattern permission table in the filter | One place to read; drifts from the controllers and fails open for new paths unless maintained |
| Plaintext tokens per role | Simplest; tokens leak with the configuration, and actors remain anonymous |
| **Hashed named tokens + annotations + deny by default** | Least privilege and named audit actors now; token rotation is a configuration change (new hash) and restart |

## Consequences
- **Operator setup:**
  - generate a token (`openssl rand -hex 32`) and give it to the operator;
  - put only its SHA-256 (`printf %s "$TOKEN" | shasum -a 256`) in `PG_ADMIN_USERS` or the configuration;
  - revoke by removing the entry.
- **Audit:** the audit log now names the operator for every admin change.
- **Maker-checker** for money-moving manual actions stays out of scope. Admin actions today never move money (ADR-016, ADR-017).
- **Changing rights:** adding or changing roles means redeploying the configuration. With OIDC they will come from the identity provider.
