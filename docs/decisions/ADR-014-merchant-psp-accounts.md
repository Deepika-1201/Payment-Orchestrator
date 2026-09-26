# ADR-014: Merchant-owned PSP accounts: encrypted credentials and account-scoped webhooks

**Status:** Accepted (2026-09-26)

## Context
In the orchestrator model (ADR-012), every merchant has its own account at each PSP. So every PSP call must be made with that merchant's credentials, and every PSP webhook is signed with a secret that **the merchant controls**. A merchant can therefore sign any payload its PSP could send. If PSP events were matched to payments by provider reference alone, one merchant could:
- mark another merchant's payments paid or failed, or
- claim another merchant's event ids so the genuine events are discarded as duplicates.

Credential mistakes are also per merchant, while circuit breakers and health scores are per PSP.

## Decision
- **Credentials:**
  - Adapters declare `credentialFields()`: name, secret flag and required flag. The admin API accepts only those fields.
  - Credentials are stored as AES-256-GCM ciphertext, with the account id as authenticated data so ciphertexts cannot be moved between rows. Secrets are shown only masked, as their last 4 characters.
  - A provider with required fields cannot be linked without them.
- **Calls:**
  - Every SPI call receives `MerchantAccount(id, merchantId, providerCode, credentials)`. `ProviderClient` resolves it through a port that the merchant module implements, so the provider module never depends on merchants.
  - Accounts resolve regardless of status: a disabled account takes no new payments, but its in-flight status checks, captures, voids, refunds and reconciliation finish.
  - A PSP rejecting credentials raises `ProviderCredentialsException`. It fails over like an outage, but it neither counts toward the PSP-wide circuit breaker nor lowers the PSP's routing score, and it is logged against the account.
- **Webhooks:**
  - Each merchant PSP account has its own endpoint, `/v1/webhooks/providers/{code}/{account_id}`, which the adapter verifies with that account's secret.
  - Events from an account endpoint may only change payments and refunds of that account's merchant; anything else is ignored and counted. The scope is persisted, so retries keep it.
  - Inbox deduplication is per (provider, account, event id).
  - The provider-wide endpoint remains for platform-level secrets.
- **Test and live keys:** separated by environment, not by a flag on every row. Sandbox and production are different deployments and databases; `pg.security.api-key-mode` sets the key prefix and mode.
- **Reconciliation** is scoped to the reporting account's merchant, and covers suspended merchants and accounts disabled within the last 7 days.

## Alternatives
| Option | Trade-off |
|---|---|
| One provider-wide endpoint, merchant found from the payload | No per-account URL, but it depends on each PSP's payload shape and must parse untrusted JSON before choosing the secret |
| Trust events by provider reference | Simplest; allows cross-tenant forgery and event-id squatting because merchants hold their own secrets |
| Per-merchant circuit breakers | Isolates credential errors, but splits PSP health data across thousands of merchants; a dedicated exception is simpler |
| `livemode` column on every table | One deployment for both modes; every query needs the filter, and test traffic shares production capacity and data |

## Consequences
- **Onboarding:** each merchant account's webhook path (`webhook_path` in the admin API) must be configured at the PSP.
- **Latency:** each PSP call decrypts the account's credentials, adding one indexed read. An in-process cache can be added if load tests show a need (Phase 17).
- **Monitoring:** credential rejections should alert per merchant (`pg.provider.call{result=credentials_rejected}`), because they silently shift traffic to other PSPs.
