# Architecture Decision Records

Format: Context → Decision → Alternatives and trade-offs → Consequences. Status values: Proposed, Accepted, Superseded.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-modular-monolith.md) | Modular monolith with `api` and `worker` roles | Accepted |
| [ADR-002](ADR-002-java-spring-boot.md) | Java 25 LTS + Spring Boot 4.1 (MVC on virtual threads) | Accepted |
| [ADR-003](ADR-003-postgresql-jdbc.md) | PostgreSQL as system of record, plain JDBC | Accepted |
| [ADR-004](ADR-004-no-broker-v1.md) | No message broker in V1; outbox/inbox tables + DB-driven workers | Accepted |
| [ADR-005](ADR-005-provider-adapter-spi.md) | Capability-based provider SPI with failure classification | Accepted |
| [ADR-006](ADR-006-idempotency.md) | Layered idempotency (API keys, domain, PSP) | Accepted |
| [ADR-007](ADR-007-state-machines.md) | Separate Payment / Attempt / Refund state machines, row-locked aggregate | Accepted |
| [ADR-008](ADR-008-card-data-scope.md) | PAN never enters V1; CDE boundary reserved | Accepted |
| [ADR-009](ADR-009-routing.md) | DB-stored declarative routing rules + health scoring | Accepted |
| [ADR-010](ADR-010-aws-deployment.md) | AWS: ECS Fargate + Aurora PostgreSQL, Mumbai / Hyderabad, Terraform | Accepted |
| [ADR-011](ADR-011-no-redis-v1.md) | No Redis in V1 | Accepted |
| [ADR-012](ADR-012-orchestrator-shadow-ledger.md) | Orchestrator money flow with a shadow double-entry ledger | Accepted |
| [ADR-013](ADR-013-hosted-checkout.md) | Server-rendered hosted checkout with capability URLs | Accepted |
| [ADR-014](ADR-014-merchant-psp-accounts.md) | Merchant-owned PSP accounts: encrypted credentials and account-scoped webhooks | Accepted |
| [ADR-015](ADR-015-data-retention.md) | Data retention by batched deletes; financial records never deleted by the application | Accepted |
| [ADR-016](ADR-016-review-queue-and-risk.md) | Manual review queue with reasoned flags; risk outside the row lock, external vendor fails open to review | Accepted |
| [ADR-017](ADR-017-reconciliation-sla-and-report.md) | Reconciliation exceptions get an owner and an SLA; daily report computed on request | Accepted |
| [ADR-018](ADR-018-disputes.md) | Disputes as their own aggregate from PSP webhooks and reports; chargeback ledger postings; refund guard | Accepted |
| [ADR-019](ADR-019-admin-roles.md) | Admin roles: hashed named operator tokens, deny-by-default endpoint permissions | Accepted |
| [ADR-020](ADR-020-merchant-rate-limit-overrides.md) | Per-merchant rate-limit overrides loaded with API-key authentication | Accepted |
| [ADR-021](ADR-021-checkout-upi-qr.md) | UPI QR on the hosted checkout as server-rendered inline SVG (strict CSP unchanged) | Accepted |
| [ADR-022](ADR-022-application-security-hardening.md) | Application security hardening: API headers and HSTS, body limits, webhook source allowlists, log redaction, production guard | Accepted |
| [ADR-023](ADR-023-admin-sso.md) | Admin single sign-on: OIDC JWT access tokens verified against the IdP's JWKS, roles from a claim | Accepted |
| [ADR-024](ADR-024-ledger-adjustments-maker-checker.md) | Manual ledger adjustments with maker-checker (second operator approves; enforced in the database too) | Accepted |
| [ADR-025](ADR-025-data-key-rotation.md) | Data key ring (key id in ciphertexts) and audited re-encryption; keys from Secrets Manager under KMS | Accepted |
| [ADR-026](ADR-026-database-least-privilege.md) | Database least privilege: migrator vs. app role, grants re-applied after every migration, verified TLS | Accepted |
| [ADR-027](ADR-027-observability-slos-and-alerts.md) | SLOs, burn-rate alerts, dashboards and runbooks as code; zero-initialized alert counters; promtool and contract tests | Accepted |
| [ADR-028](ADR-028-terraform-aws.md) | Terraform for AWS: one region module for primary and standby, edge and IAM controls, ephemeral secrets, mocked tests and Trivy in CI | Accepted |
