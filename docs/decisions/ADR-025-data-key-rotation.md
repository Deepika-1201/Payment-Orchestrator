# ADR-025: Data key ring and re-encryption for secrets at rest

**Status:** Accepted (2026-09-26)

## Context
Merchant webhook secrets and PSP credentials are encrypted with AES-256-GCM, and credentials are additionally bound to their row (ADR-014). Until now there was a single data key from configuration, with no key id in the ciphertext. Rotating it, whether on schedule or after a suspected leak, would have made every stored secret unreadable. Architecture §8 names KMS envelope encryption as the target: data keys protected by a KMS key, the application encrypting records with the data keys.

## Decision
- **Key ring:**
  - `pg.security.data-encryption-keys` lists `{id, key}` entries (base64, 32 bytes each).
  - `pg.security.primary-data-key-id` names the key that encrypts new data.
  - The old single `data-encryption-key` keeps working as the key with id `legacy`.
- **Ciphertext format v2:** `[2][id length][key id][iv][ciphertext+tag]`. Version-1 ciphertexts (`[1][iv][…]`) are read with the `legacy` key. Authenticated context (AAD) is unchanged, so credentials stay bound to their account row.
- **Rotation procedure:**
  1. Add the new key to the ring and make it primary; the old key stays in the ring. Deploy.
  2. Call `POST /admin/v1/security/data-keys/re-encrypt` (permission `security_write`, `admin` role only). It rewrites every webhook secret, previous secret and credential set still under an older key. Each row is swapped only if unchanged since it was read, so a concurrent secret rotation wins.
  3. Check `GET /admin/v1/security/data-keys` until it reports only the primary key.
  4. Remove the old key from the ring and deploy.
- **Audit:** each run is audited (`data_keys.re_encrypted`, with the counts per key).
- **KMS envelope in AWS:** the data keys live in Secrets Manager, encrypted at rest by a customer-managed KMS key (Terraform). ECS injects them into the task at start. Access to the secret and to `kms:Decrypt` is granted only to the API and worker task roles. The application never sees the KMS key itself.

## Alternatives
| Option | Trade-off |
|---|---|
| KMS `Encrypt`/`Decrypt` per secret | No data key in memory; a KMS call on every webhook signature and PSP call, plus KMS quotas and latency on the payment path |
| `GenerateDataKey` per record (full envelope per row) | Key per record; more code and a KMS call per write, with little gain for a few thousand secrets |
| Re-encrypt in a migration | Runs once and automatically; migrations cannot hold the new key, and a mistake is irreversible |
| **Key ring from Secrets Manager (KMS-encrypted), explicit re-encryption** | Rotation without downtime or KMS on the hot path; the keys are in task memory, as with any data key |

## Consequences
- **Old keys:** they must stay in the ring until the key usage shows zero rows under them. Removing one too early makes those secrets unreadable, and startup does not detect it; decryption fails on first use.
- **Key ids:** they are visible in ciphertext headers. They are identifiers, not secrets.
- **Backups:** backups taken before a rotation need the old key to restore. Keep retired keys in Secrets Manager, disabled rather than deleted, for the backup retention period.
