# Requirements — Payment Gateway V1

| | |
|---|---|
| Phase | 2 — Requirements (baseline) |
| Status | Approved: Phase 1 defaults accepted on 2026-09-26 |
| Next | [architecture.md](architecture.md) (HLD) → [low-level-design.md](low-level-design.md) (LLD) → [decisions/](decisions/README.md) (ADRs) |

Items marked **(assumed)** were not explicitly discussed and stay open for challenge.

---

## 1. Product summary

A merchant-facing payment gateway built around a **provider-agnostic orchestrator**. Merchants integrate once through a REST API. The platform validates each payment, risk-checks it, routes it to one of several PSPs through pluggable adapters, drives it through explicit state machines, reconciles it against PSP data, and notifies the merchant through signed webhooks.

V1 is a **reference implementation** (mock providers + PSP sandboxes, no real money) engineered to commercial standards: compliance controls are designed in and documented, not certified.

## 2. Scope decisions (from Phase 1)

| # | Topic | Decision |
|---|---|---|
| Q1 | Intent | Reference implementation, built to commercial standards |
| Q2 | Money flow | **Orchestrator** — merchants hold their own PSP accounts, funds flow PSP → merchant, the platform never holds funds. Payment-Aggregator mode (escrow, settlement, merchant balances) is a designed-for extension |
| Q3 | Surfaces | Server-to-server REST API returning `next_action`; minimal hosted checkout for demos (later phase); RBAC-protected admin APIs instead of dashboards |
| Q4 | V1 methods | UPI (Intent, QR; Collect optional), cards (one-time, 3DS via PSP, auth/capture/void), netbanking. Wallets, EMI, BNPL, bank transfer/virtual accounts, international: designed for, not built |
| Q5 | Recurring | Modeled in the domain, built after V1 |
| Q6 | Lifecycle | No partial capture; multiple partial refunds; disputes = ingest + track + ledger impact |
| Q7 | Scale | ~1M payments/day at launch; 1,000 TPS peak design point; shard-ready keys for 10× growth; API overhead p99 ≤ 150 ms excluding PSP time |
| Q8 | Card data | PAN never touches V1 (PSP-hosted fields/redirects, network tokens); card-data-service (CDE) boundary reserved |
| Q9 | PSPs | Mock providers first, then Razorpay and Cashfree sandboxes; PSP REST APIs only, no direct ISO 8583/acquirer links |
| Q10 | Routing | Rule-based priority/weighted lists, health-based failover and dynamic success-rate/latency scoring; cost optimization and bandits later |
| Q11 | SLOs / DR | 99.95% availability; RPO 0 in-region, ≤ 1 min cross-region; RTO ≤ 30 min regional; warm standby in a second Indian region |
| Q12 | Late success | Merchant-configurable; default **auto-refund** |
| Q13 | Cloud | AWS — ap-south-1 (Mumbai) primary, ap-south-2 (Hyderabad) DR; ECS Fargate; Terraform; deployed on demand |
| Q14 | Stack | Java 25 LTS + Spring Boot 4.1 |

## 3. Actors

| Actor | Interaction |
|---|---|
| Merchant backend | Calls the REST API with a secret API key; receives signed webhooks |
| Customer (payer) | Completes customer actions: UPI app approval, QR scan, PSP-hosted card page / 3DS, bank login |
| PSP | Receives server-to-server calls; sends webhooks; provides status APIs and reports |
| Platform admin / ops / finance | Uses admin APIs: merchants, keys, routing rules, provider health, webhook replay, reconciliation exceptions |

## 4. Functional requirements

### 4.1 Merchant and access management
- **FR-M1** Admins create merchants, link PSP accounts (provider code + encrypted credentials), and configure webhook URL, payment expiry, and late-success policy.
- **FR-M2** Merchants authenticate with secret API keys. Keys are shown once, stored only as hashes, revocable, and several can be active at once for rotation.
- **FR-M3** Each merchant has a webhook signing secret: shown once, encrypted at rest, rotatable.

### 4.2 Payments
- **FR-P1** Create a payment (amount in paise, currency, merchant order id, capture method, customer, metadata, expiry). Idempotent.
- **FR-P2** Confirm a payment with a payment method. The response carries `next_action` when the customer must act (redirect, UPI intent, QR, or waiting for collect approval).
- **FR-P3** A payment can have several attempts (customer retries with another method, or platform failover). At most one attempt is active at a time, and the funds of at most one attempt are kept: extra successes are refunded automatically.
- **FR-P4** Retrieve a payment with its attempts and refund totals.
- **FR-P5** Manual capture of authorized card payments (full amount only in V1); automatic capture otherwise, including capturing on the merchant's behalf when a PSP only authorizes.
- **FR-P6** Cancel before success; an authorized payment is voided.
- **FR-P7** Payments expire (default 15 min, configurable 1 min–24 h). A payment waiting on a PSP outcome gets a grace period (default 30 min) before expiring. Authorizations lapse after a TTL (default 5 days) and are voided.
- **FR-P8** Late success (the PSP confirms after expiry or failure) follows the merchant's policy: `AUTO_REFUND` (default) or `ACCEPT`. After a merchant cancel the money is always refunded.
- **FR-P9** Every state change is written to an append-only transition log with its source: `API`, `PROVIDER_RESPONSE`, `PROVIDER_WEBHOOK`, `STATUS_CHECK`, `RECONCILIATION`, or `SYSTEM`.

### 4.3 Payment methods (V1)
- **FR-PM1** UPI Intent returns `next_action.upi_intent.uri`; UPI QR returns `next_action.display_qr`; UPI Collect (optional) returns `requires_action` with `next_action.type = await_approval` until the customer approves in their UPI app.
- **FR-PM2** Cards run on PSP-hosted pages/fields (redirect), with 3DS handled by the PSP. Only tokens and non-sensitive metadata (network, last 4) are kept.
- **FR-PM3** Netbanking uses bank selection plus redirect.
- **FR-PM4** A new method (wallet, EMI, BNPL, bank transfer) is added by implementing an adapter and declaring capabilities. The core payment domain does not change.

### 4.4 Routing and provider management
- **FR-R1** Candidate providers are the merchant's linked providers whose declared capabilities support the method, flow, currency, amount, and capture mode.
- **FR-R2** Routing rules (global or per merchant, priority-ordered, first match wins) have conditions on method, UPI flow, amount, currency, bank, and capture method, and use one of three strategies: `PRIORITY`, `WEIGHTED`, or `DYNAMIC`. Rules are data in the database and can be changed at runtime without a deploy.
- **FR-R3** Provider health: success rate per provider and method (counting only provider-attributable failures) and latency, over sliding windows. Circuit breakers exclude unhealthy providers.
- **FR-R4** Failover to the next candidate happens only when the previous provider **definitely did not process** the request (connection refused, circuit open). Unknown outcomes are never failed over.

### 4.5 Inbound PSP webhooks and status resolution
- **FR-W1** Accept PSP webhooks at `/v1/webhooks/providers/{provider}`. Verify the signature, reject invalid requests, store the raw event, deduplicate on the provider event id, acknowledge fast, and process with retries (inbox pattern).
- **FR-W2** Verify amount and currency of a PSP-reported success against the attempt. Mismatches are not applied; they are flagged for reconciliation.
- **FR-W3** Poll PSP status, with backoff, for attempts and refunds that are `UNKNOWN`, `PENDING`, or waiting on the customer. Respect PSP/NPCI status-check rate limits. Escalate anything unresolved after 72 h.
- **FR-W4** Duplicate or out-of-order events must never move state backwards (transitions are monotonic).

### 4.6 Merchant notifications (outbound webhooks)
- **FR-N1** Events: `payment.authorized`, `payment.succeeded`, `payment.attempt_failed`, `payment.failed`, `payment.cancelled`, `payment.expired`, `refund.succeeded`, `refund.failed`.
- **FR-N2** At-least-once delivery with an HMAC-SHA256 signature over timestamp + body. Retries use exponential backoff with jitter for about 48 h; after that the delivery is dead-lettered and can be replayed.
- **FR-N3** Each event carries an event id (for dedupe), `created_at`, and the resource version (for ordering).
- **FR-N4** Webhook URLs must be HTTPS and must not resolve to private or internal addresses (SSRF protection).

### 4.7 Refunds
- **FR-RF1** Full, partial, and multiple partial refunds against the succeeded attempt. The sum of non-failed refunds must never exceed the captured amount, including under concurrent requests.
- **FR-RF2** Refunds are idempotent (`Idempotency-Key`, plus an optional `merchant_refund_id` unique per merchant).
- **FR-RF3** Refund lifecycle is tracked independently: `INITIATED → PENDING → SUCCEEDED | FAILED`, with `UNKNOWN` on timeout.
- **FR-RF4** System-initiated refunds cover late successes and duplicate successes.

### 4.8 Risk
- **FR-RK1** Risk is evaluated before routing and returns `ALLOW`, `REVIEW`, `CHALLENGE`, or `BLOCK` with reasons.
- **FR-RK2** V1 rules: amount limits, blocklists (customer, VPA, IP), velocity per customer. `REVIEW` is allowed and flagged. `CHALLENGE` means 3DS for cards, which is always on in V1.
- **FR-RK3** External fraud providers plug in behind the same interface.

### 4.9 Reconciliation (Phase 13)
- **FR-RC1** Ingest PSP transaction and settlement reports (API, SFTP, or file) daily for each merchant PSP account.
- **FR-RC2** Match on provider reference, amount, currency, and status. Exception classes: missing internally, missing at PSP, amount mismatch, status mismatch, duplicate, settlement mismatch.
- **FR-RC3** Auto-heal safe cases (for example, a PSP success we missed is applied with source `RECONCILIATION`). Everything else goes to an exceptions queue with an owner and an SLA.
- **FR-RC4** Daily reconciliation report per merchant and provider.

### 4.10 Ledger (Phase 13)
- **FR-L1** An immutable double-entry *shadow* ledger tracks, per merchant PSP account: captures, refunds, PSP fees, chargebacks, settlements. Each transaction balances, and corrections are made only by reversal entries.
- **FR-L2** The PSP receivable per merchant and provider nets to zero after settlement. Any residual raises a reconciliation exception.

### 4.11 Disputes (after core V1)
- **FR-D1** Ingest chargebacks and UPI disputes from PSP webhooks and reports; track status, ledger impact, and merchant events.

### 4.12 Admin and audit
- **FR-A1** Admin APIs cover merchants, API keys, provider accounts, routing rules, provider health, webhook replay, and reconciliation exceptions.
- **FR-A2** Every admin action is audited (actor, action, resource, details, request id) in an append-only log.

## 5. Non-functional requirements

| ID | Category | Requirement |
|---|---|---|
| NFR-1 | Throughput | 1,000 payment TPS peak (create + confirm); ~12 TPS average at launch; stateless API and worker tasks scale horizontally |
| NFR-2 | Latency | API overhead excluding PSP time: p50 ≤ 40 ms, p99 ≤ 150 ms. Webhook acknowledgement p99 ≤ 200 ms |
| NFR-3 | Availability | 99.95% monthly for payment APIs and inbound webhooks (≈ 22 min/month) |
| NFR-4 | Durability | RPO 0 in-region; ≤ 1 min cross-region |
| NFR-5 | Recovery | RTO ≤ 5 min for an AZ failure (automatic); ≤ 30 min for a region failure (runbook) |
| NFR-6 | Correctness | No double charge from platform retries; no lost transitions; refunds never exceed captures; ledger always balanced. Enforced by database constraints and tests |
| NFR-7 | Consistency | Strong for payment, refund, and ledger state (single-writer relational DB); eventual (seconds) for merchant webhooks and analytics |
| NFR-8 | Delivery semantics | At-least-once everywhere with idempotent consumers. No exactly-once claims |
| NFR-9 | Security | TLS 1.2+ (1.3 preferred); encryption at rest (KMS); secrets in Secrets Manager; API keys hashed; webhook secrets encrypted; least-privilege IAM; RBAC for admins; no PAN, CVV, or secrets in logs |
| NFR-10 | Observability | Every request traceable by `request_id`, `payment_id`, `merchant_id`, attempt id, and provider reference. RED + business metrics, OpenTelemetry traces, structured JSON logs |
| NFR-11 | Extensibility | A new PSP or method is an adapter plus configuration, with no core domain change |
| NFR-12 | Operability | Zero-downtime deploys (rolling; expand/contract migrations); liveness and readiness probes; routing changes at runtime |
| NFR-13 | Portability | Containerized; `docker compose up` locally; Terraform for cloud |
| NFR-14 | Testability | Deterministic mock PSPs with failure injection: timeouts, unknown outcomes, unavailability, declines, delayed and duplicate webhooks |
| NFR-15 | Data residency | All payment data (primary, DR, backups, logs) stored in India only |
| NFR-16 | Retention | Financial records (payments, refunds, ledger, audit) ≥ 8 years **(assumed; confirm with counsel)**; raw PSP webhooks 180 days; idempotency keys 7 days; PII minimized |

## 6. Compliance constraints designed against

| Regime | Design impact |
|---|---|
| RBI storage of payment system data (2018) | India-only regions for DB, backups, logs, and DR (ap-south-1 / ap-south-2) |
| RBI card-on-file tokenization (in force since Oct 2022) | No PAN storage anywhere. Saved cards are network tokens via the PSP; only last 4, network, and issuer are kept |
| Additional factor of authentication (3DS) for domestic card-not-present payments | Card attempts always go through PSP 3DS (`REQUIRES_ACTION` redirect) |
| RBI turnaround time (TAT) for failed transactions | Status resolution and late-success auto-refunds return customer money promptly; reconciliation runs T+1 |
| RBI e-mandate framework | Recurring payments (post-V1): pre-debit notifications and mandate lifecycle |
| PCI DSS v4.x | V1 keeps cardholder data out of our environment entirely; the CDE boundary is reserved for a future card-data service |
| DPDP Act 2023 | Minimal PII (customer email/phone optional), purpose limitation, masking in logs, retention policy, access control |
| RBI Payment Aggregator authorization | Not needed in orchestrator mode; required before enabling PA mode |
| NPCI UPI rules | Intent/QR are primary and Collect optional (collect flows are being curtailed); resolver backoff respects status-check limits |

## 7. Out of scope for V1

Holding funds and paying out settlements (PA mode) · recurring/mandates · EMI, BNPL, wallets, bank transfer, international cards, FX · partial capture · dispute evidence workflow · merchant dashboard UI · card vault/CDE · direct acquirer (ISO 8583) integrations · ML-based fraud · multi-region active-active · merchant billing/invoicing.

## 8. Open assumptions (defaults applied until challenged)

| Topic | Default |
|---|---|
| Merchant onboarding | Manual, via admin API. KYC is done by the merchant's PSPs (orchestrator mode) |
| Fraud signals | Customer id, email, phone, IP, device id, VPA, amount. No device-fingerprinting SDK in V1 |
| Webhook SLA | First delivery attempt p95 < 5 s after the state change; retries for ~48 h; no ordering guarantee (use `version`) |
| Test vs live | API keys carry a mode; V1 issues `TEST` keys only |
| Currencies | INR only in V1; the model stores ISO 4217 codes and integer minor units throughout |
| Limits | Per payment ₹1 – ₹10,00,000 at the API; per-method limits come from provider capabilities (e.g. UPI ₹1,00,000 by default) |

## 9. Glossary

| Term | Meaning |
|---|---|
| PSP | Payment service provider (e.g. Razorpay, Cashfree) reached through an adapter |
| Orchestrator | Platform that routes payments across PSPs without holding funds |
| PA | Payment Aggregator: RBI-authorized entity that pools funds in escrow and settles to merchants |
| Payment | The merchant's intent to collect an amount for an order (one per checkout) |
| Attempt | One try of a payment at one PSP with one method |
| Late success | A PSP confirms success after the platform expired, failed, or cancelled the payment |
| Duplicate success | A second attempt succeeds after another attempt already succeeded |
| Unknown outcome | The PSP call timed out or was ambiguous; the outcome is resolved later, never assumed |
| VPA | UPI virtual payment address (e.g. `name@bank`) |
| AFA / 3DS | Additional factor of authentication for card-not-present payments |
| CoFT | Card-on-file tokenization (RBI) |
| CDE | Cardholder data environment (PCI DSS scope) |
| TAT | RBI turnaround time for resolving failed transactions |
| Paise | Minor unit of INR; all amounts are integers in minor units |
