# ADR-026: Database least privilege and verified TLS

**Status:** Accepted (2026-09-26)

## Context
Architecture §8 says the application's database user has no DDL rights and that migrations run under a separate role. Until now the application and Flyway shared one user, which owned the schema. Anyone who compromised the application (SQL injection, a leaked task credential) could:
- drop or alter tables, or truncate them;
- disable the append-only triggers on the ledger and audit log, which the owner may do, and then rewrite history.

Nothing forced the database connection to use TLS or to check the server certificate either.

## Decision
- **Two roles** (`deploy/db/roles.sql`, run once by the DBA or Terraform with the master user):
  - `gateway_migrator` owns the schema and runs Flyway.
  - `gateway_app` is what the API and worker tasks use.
  - Names cannot start with `pg_`, which PostgreSQL reserves.
  - The app role gets `statement_timeout` 30 s and `idle_in_transaction_session_timeout` 60 s.
- **Grants are part of the migrations:** a Flyway `afterMigrate.sql` callback runs after every migration and grants the app role (`DB_APP_ROLE`):
  - `SELECT/INSERT/UPDATE/DELETE` on all tables and sequence usage;
  - no `CREATE` on the schema and no `TRUNCATE`, `REFERENCES` or `TRIGGER`;
  - no `UPDATE`/`DELETE` on `ledger_transactions`, `ledger_entries`, `audit_log` or `payment_transitions`;
  - no access to `flyway_schema_history`.

  New tables get these grants on the next deploy, so grants cannot drift from the schema. The callback does nothing when no app role is configured, as in local development and tests.
- **Append-only history is protected twice:** the permission check rejects the write (`42501`), and the V1 triggers remain as a second layer for the owner.
- **Migrations run as a separate step:**
  - `spring.flyway.user`/`password` default to the datasource and are overridden by `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD`.
  - In `prod`, `spring.flyway.enabled` defaults to false. A one-off ECS task runs the same image with `PG_MIGRATE_ONLY=true` plus the migrator secret, then exits.
  - That task (`MigrationTask`) starts only a DataSource and Flyway: no web server, no data keys, no PSP credentials. The migrator secret is the only secret it receives, and it too refuses a `prod` URL without `sslmode=verify-full`.
  - API and worker tasks therefore never hold DDL credentials, and the migration task never holds application secrets.
- **TLS with certificate verification:**
  - The image includes the Amazon RDS CA bundle at `/app/certs/rds-global-bundle.pem`.
  - `ProductionConfigurationGuard` refuses to start `prod` unless the datasource URL has `sslmode=verify-full`.
  - Aurora also enforces TLS server-side (`rds.force_ssl = 1`, Terraform).

## Alternatives
| Option | Trade-off |
|---|---|
| One owner user (status quo) | Simple; a compromised application can change the schema and rewrite history |
| Grants in each versioned migration | Explicit; easy to forget for a new table, and role names differ per environment |
| `ALTER DEFAULT PRIVILEGES` only | Covers new tables automatically; still grants `UPDATE`/`DELETE` on new append-only tables and cannot express the exceptions |
| IAM database authentication | No static password; 15-minute tokens need a custom DataSource and add a connection-rate limit, a possible later step |
| **Two roles, grants re-applied by an `afterMigrate` callback, migration as a separate task** | Least privilege that follows the schema automatically; one more task in the deploy pipeline |

## Consequences
- **Retention jobs** delete only from tables the app may delete from (webhook events, deliveries, idempotency records). A future job that must delete from an append-only table would need a migrator-run procedure, by design.
- **Session settings:** the role-level timeouts apply to every app session. Long reports must use pagination (they already do) or run elsewhere.
- **Local and CI** keep the single superuser. `DatabaseLeastPrivilegeIntegrationTest` creates a real app role in the embedded database, applies the actual callback and checks every forbidden statement fails with `insufficient_privilege`. It also confirms normal data access works.
- **Migration task tests:** `MigrationTaskIntegrationTest` runs the task against a fresh database with nothing but database settings and checks that migrations and grants apply. It also checks that under `prod` the task refuses a URL without `sslmode=verify-full` before touching the schema.
