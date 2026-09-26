# Payment Gateway

A payment gateway reference implementation built around a multi-PSP orchestrator. It is designed for India first (UPI, cards, netbanking) and built to commercial engineering standards: explicit state machines, layered idempotency, unknown-outcome handling, a transactional outbox, signed webhooks, and routing based on PSP capabilities and health.

> Status: phases 1–9 and 13 are complete, plus core webhooks, status resolution, expiry and refunds. That includes the double-entry shadow ledger and PSP reconciliation. See the [Roadmap](#roadmap).

## Documentation

| Doc | Contents |
|---|---|
| [docs/requirements.md](docs/requirements.md) | Scope decisions, functional and non-functional requirements, compliance constraints |
| [docs/architecture.md](docs/architecture.md) | HLD: context, modules, flows (UPI, card, refund, webhooks, reconciliation, failure handling), deployment, DR |
| [docs/low-level-design.md](docs/low-level-design.md) | Domain model, state machines, algorithms, provider SPI, routing, idempotency, schema, API, error codes |
| [docs/decisions/](docs/decisions/README.md) | ADR-001 … ADR-012 |

## Quick start

JDK 25 is required. Docker is optional.

```bash
# Without Docker: embedded PostgreSQL 17, mock PSPs, workers on, http://localhost:8080
./gradlew bootTestRun

# With Docker
docker compose up --build

# In another terminal: end-to-end demo (onboard → pay via UPI → webhook → refund → timeout recovery)
./scripts/demo.sh
```

The `local` profile is for development only. It uses the admin token `local-admin-token`, enables the mock PSPs and simulator, and relaxes the webhook URL policy.

```bash
./gradlew test    # unit, integration (embedded PostgreSQL) and architecture tests
./gradlew build   # compile (-Werror), test, package
```

## API at a glance

```bash
# Admin: onboard a merchant and issue an API key (both secrets are shown once)
curl -X POST localhost:8080/admin/v1/merchants -H 'Authorization: Bearer local-admin-token' \
  -H 'Content-Type: application/json' -d '{"name":"Store","providers":["MOCK_ALPHA","MOCK_BETA"]}'
curl -X POST localhost:8080/admin/v1/merchants/{merchant_id}/api-keys -H 'Authorization: Bearer local-admin-token'

# Merchant: create, then confirm (every POST needs an Idempotency-Key)
curl -X POST localhost:8080/v1/payments -H "Authorization: Bearer $KEY" -H 'Idempotency-Key: k1' \
  -H 'Content-Type: application/json' -d '{"amount":49900,"currency":"INR","merchant_order_id":"order_1"}'
curl -X POST localhost:8080/v1/payments/{id}/confirm -H "Authorization: Bearer $KEY" -H 'Idempotency-Key: k2' \
  -H 'Content-Type: application/json' -d '{"payment_method":{"type":"upi","upi":{"flow":"intent"}}}'
```

| Endpoint | Purpose |
|---|---|
| `POST /v1/payments` · `GET /v1/payments/{id}` | Create / retrieve |
| `POST /v1/payments/{id}/confirm` · `/capture` · `/cancel` | Start an attempt / capture an authorization / cancel or void |
| `POST /v1/payments/{id}/refunds` · `GET /v1/payments/{id}/refunds` · `GET /v1/refunds/{id}` | Refunds |
| `POST /v1/webhooks/providers/{code}` | PSP webhooks (signature-authenticated) |
| `/admin/v1/merchants` · `/routing-rules` · `/providers/health` · `/webhook-deliveries` | Admin |
| `GET /admin/v1/ledger/balances?merchant_id=` · `GET /admin/v1/ledger/transactions?reference_id=` | Shadow ledger balances and postings |
| `POST /admin/v1/reconciliation/runs` · `GET /admin/v1/reconciliation/exceptions` · `POST …/exceptions/{id}/resolve` | Reconcile a merchant PSP account for a window; work the exception queue |
| `/simulator/...` | Mock PSP hosted page, completion, outage simulation and settlement-report anomalies (local and test only) |

Mock PSP test scenarios are selected by the last two digits of the amount:

| Suffix | Payment scenario |
|---|---|
| `01` | Timeout, but the PSP processed the payment |
| `03` | Issuer decline |
| `04` | Pending, then succeeds without sending a webhook |
| `05` | Timeout, and the PSP never processed the payment |

| Suffix | Refund scenario |
|---|---|
| `07` | Pending |
| `08` | Timeout, but processed |
| `09` | Failed |

## Project layout

```
src/main/java/com/payments/gateway/
  shared/        money, ids, errors, crypto, JSON, request-id + problem-details handling, SSRF guard
  merchant/      merchants, API keys, auth filters, admin API
  idempotency/   Idempotency-Key storage and replay
  provider/      SPI (spi/), registry, circuit-breaking client, health tracker, mock PSPs (mock/)
  routing/       rules (DB), strategies, routing engine, admin API
  risk/          risk engine and rules
  payment/       domain (state machines), application (orchestration, outcomes, refunds, resolver, expiry),
                 infrastructure (JDBC), api (DTOs/mapper), web (controllers)
  webhook/       inbound PSP inbox, outbound merchant outbox + delivery worker
  ledger/        double-entry shadow ledger (postings on capture/refund/fee/settlement), admin API
  reconciliation/ settlement-report matching, auto-heal, exception queue, admin API
  platform/      worker scheduler (incl. daily T+1 reconciliation at 02:30 IST)
src/main/resources/db/migration/   Flyway schema
src/test/java/...                  unit, integration and ArchUnit tests + LocalDevApplication
```

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1–5 | Discovery, requirements, HLD, LLD, ADRs | Done |
| 6 | Scaffolding: Gradle, Docker, CI, Flyway | Done |
| 7 | Payment domain, state machines, idempotency, payment API | Done |
| 8 | Provider SPI, mock PSPs, simulator | Done |
| 9 | Routing engine (rules + health + circuit breakers) | Done |
| 11–12 | Webhooks (inbound inbox, outbound outbox), refunds, status resolver, expiry | Done (core) |
| 10 | Real PSP adapters (Razorpay, Cashfree sandboxes) + contract tests | Next |
| 13 | Reconciliation + shadow double-entry ledger | Done |
| 14 | Risk: external provider adapter, review queue | Planned |
| 15 | Observability: dashboards, SLO alerts, OTel collector in compose | Planned |
| 16 | Terraform (AWS ECS Fargate, Aurora, WAF, DR) | Planned |
| 17 | Load tests (k6), production-readiness review | Planned |
