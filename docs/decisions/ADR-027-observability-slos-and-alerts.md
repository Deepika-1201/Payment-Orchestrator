# ADR-027: SLOs, alerts and dashboards as code

**Status:** Accepted (2026-09-27). Note (2026-09-27): running the stack under load found three gaps, since fixed. The 5xx series only exists after the first error, so error ratios now default the numerator to zero (`or vector(0)`); before, a clean period recorded nothing and the availability panels showed "No data". `pg_webhooks_inbound_total{result="invalid|source_rejected|retry"}` is now registered at zero per provider, so `ProviderWebhooksRejected` sees the first burst. Open circuits now turn half-open on a timer, because routing never calls an open provider and so it was never retried after an outage.

## Context
The application exported useful metrics, but nothing watched them. Architecture §9 lists the alerts operations needs:
- the provider success rate falling below baseline;
- provider p99 latency;
- attempts unknown for over an hour;
- DEAD webhook deliveries;
- error-budget burn.

NFR-2 and NFR-3 set latency and availability targets that nobody measured.

Two gaps in the metrics themselves blocked this:
- Nothing reported the age of unknown attempts or the merchant webhook backlog.
- Rare-event counters only appeared on their first event. Prometheus cannot see a series' first increment, so an `increase() > 0` alert on a new series never fires for that event. That is precisely the amount mismatch, DEAD delivery or evidence conflict an alert exists to catch.

## Decision
- **Rules as code:** `deploy/observability/prometheus/rules/payment-gateway.yml` is one file with two groups:
  - Recording rules for the SLIs.
  - 21 alerts, each with severity `critical` (page) or `warning` (ticket), a summary and a `runbook_url` into [docs/runbooks.md](../runbooks.md).

  The same file loads into the local Prometheus and into Amazon Managed Prometheus.
- **SLOs:**
  - Availability (NFR-3, 99.95 %) is the share of `/v1/**` requests (payment API and PSP webhooks) answered without a 5xx. Multi-window burn-rate alerts watch it: page at 14.4× over 1 h and 5 min, or 6× over 6 h and 30 min; ticket at 3× over 1 d and 2 h, or 1× over 3 d and 6 h.
  - Latency (NFR-2) is p99 of requests that never call a PSP (reads and payment creation), ≤ 150 ms, and p99 of PSP webhook acknowledgements, ≤ 200 ms. Requests that call a PSP are covered by provider latency, since their time is mostly the PSP's.
- **PSP health:** a provider's success ratio over 30 minutes is compared with its own ratio over the last day, because customer declines make a fixed target meaningless. Also alerted: p99 latency above 3 s, and more than 5 % failed, timed-out, rejected or short-circuited calls.
- **New signals in the code:**
  - `pg.attempts.unknown` (count) and `pg.attempts.unknown.oldest.age` (seconds), backed by a partial index (V12).
  - `pg.webhook.deliveries.due` and `.lag` (seconds the oldest due delivery has waited).
  - Latency histograms for `http.server.requests` and `pg.provider.call`, with exact buckets at 40, 150 and 200 ms.
- **Zero-initialized counters:** counters that alerts watch with `increase()` are registered at zero for every known tag value at startup: DEAD/retry/succeeded deliveries, mismatches and conflicts per evidence source, admin denials per permission, rate limiting, risk rule errors, status-check outcomes, late successes.
- **Dashboard:** *Payment Gateway - Overview* (Grafana JSON, provisioned) covers SLOs, payments, PSPs, unknown outcomes and webhooks, money safety, security and runtime. A datasource variable lets the same JSON import into Amazon Managed Grafana.
- **Local stack:** `docker compose --profile observability up` adds Prometheus, Grafana, Tempo and an OpenTelemetry Collector. The application exports traces to the collector as it does to ADOT in AWS. The collector strips `Authorization` and `Cookie` span attributes as a second line of defence.
- **Verification:**
  - `promtool test rules` feeds synthetic series to the key alerts and checks each fires, or stays quiet, when it should. Each burn-rate window pair is isolated so a wrong threshold in either fails a test.
  - `ObservabilityContractIntegrationTest` fails if a rule or dashboard queries a series the application does not export after one payment, or if an alert lacks a severity, summary or runbook section.
  - CI starts the whole stack and `verify-stack.sh` checks it end to end: scrape, rule health, dashboard provisioning, and traces reaching Tempo.

## Alternatives
| Option | Trade-off |
|---|---|
| Alerts clicked together in Grafana or CloudWatch | Quick; not reviewed, versioned or tested, and drifts from the code |
| CloudWatch alarms on EMF metrics | Native to AWS; no PromQL, weaker histograms, different locally and in AWS |
| Threshold alerts on raw error rate | Simple; noisy at low traffic and blind to slow budget burn |
| Fixed success-rate target per PSP | Easy to explain; wrong whenever the customer mix shifts |
| **Rules, dashboards and runbooks in the repository, unit-tested, one file for local and AWS** | Reviewed and tested like code; thresholds are starting points to tune with real traffic |

## Consequences
- **Renamed counter:** `pg.payments.created` is now `pg.payments`. Prometheus reserves the `_created` suffix, so the counter always exported as `pg_payments_total`, and documentation that said `pg_payments_created_total` was wrong. The contract test found it.
- **Tag values:** a new tag value on a watched counter (a new evidence source, permission or risk rule) is registered at startup automatically. Where tag values are open-ended (provider codes), alerts use thresholds above one event.
- **Database load:** the gauges run small indexed queries on each scrape, in every task. Dashboards use `max()` because every task reports the same database-derived value.
- **Tuning:** thresholds such as 25 open reviews, a 3 s PSP p99 and 5 minutes of outbox lag are starting points. Tune them after load tests (phase 17) and real traffic, and keep the promtool tests in step.
