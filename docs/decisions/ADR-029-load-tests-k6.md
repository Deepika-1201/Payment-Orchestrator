# ADR-029: Load tests with k6

**Status:** Accepted (2026-09-27)

## Context
NFR-1 asks for 1,000 payment TPS at peak and about 12 at launch. NFR-2 sets p99 API overhead at 150 ms or less without PSP time, and p99 webhook acknowledgement at 200 ms or less. The alert thresholds from ADR-027 were explicitly provisional until load tests existed. Nothing exercised the full payment path under load.

## Decision
- **One script, four profiles:** `load-tests/payment-flow.js` runs the real path for each payment.
  1. Create the payment.
  2. Confirm it with UPI intent.
  3. Approve it at the mock PSP, which posts a signed webhook to the gateway.
  4. Read the payment and require status `succeeded`.

  Profiles use arrival-rate executors, so a slow system shows up as latency and dropped iterations, not as silently lower load.

  | Profile | Payments per second | Duration | Purpose |
  |---|---|---|---|
  | `smoke` | 2 | 30 s | Every change (CI) |
  | `steady` | 100 (override with `RATE`, `DURATION`) | 10 min | Sustained load, leaks, pool sizing |
  | `peak` | ramp to 1,000 (NFR-1) | 16 min | Capacity and autoscaling |
  | `spike` | 100 → 500 in 10 s | 7.5 min | Burst handling and recovery |
- **Thresholds encode the requirements.** Any breach makes k6 exit non-zero.
  - p99 of `create` and `read` under 150 ms (NFR-2); `confirm`, which includes the mock PSP call, under 500 ms.
  - Under 0.1 % failed requests, and over 99.9 % of payments reach `succeeded`.
  - No dropped iterations for `smoke` and `steady`.
- **Server-side checks:** with `METRICS_URL` set, the script reads the application's own histograms at the end (ADR-027 buckets). At least 99 % of `POST /v1/payments` must complete within 150 ms and of PSP webhooks within 200 ms. The client never sees webhook acknowledgements; the server's measurement is the one NFR-2 means.
- **Load spread across merchants:** setup creates several merchants and raises their rate limits (ADR-020), so the test measures the gateway rather than one merchant's budget. Every payment uses a new customer: reusing one per virtual user trips the risk velocity rule (ADR-016), as it should.
- **Where it runs:** only against deployments with mock PSPs: `bootTestRun`, compose, or a dedicated load-test environment. The `prod` profile refuses mock PSPs (ADR-022), and real PSP sandboxes must never be load tested. CI runs `smoke` against the compose stack after the observability checks.

## Local baseline (2026-09-27)
One MacBook ran the app, embedded PostgreSQL and k6 together:

| Load | Result | p99 create / read / confirm | Payment end to end |
|---|---|---|---|
| 50/s for 60 s | 3,000 / 3,000 succeeded, 0 errors | 23 / 3 / 90 ms | p99 171 ms |
| 100/s for 60 s | 6,001 / 6,001 succeeded, 0 errors | 20 / 4 / 96 ms | p99 168 ms |
| 250/s for 30 s | 7,484 / 7,484 succeeded, 0 errors, 17 dropped | 135 / 52 / 215 ms | p99 415 ms |

One instance on a laptop saturates around 250 payments/s, roughly 1,000 HTTP requests/s. Every payment still succeeded, but latency approached the NFR-2 limit and k6 fell behind. Webhook acknowledgements stayed within 200 ms throughout.

The first run failed 29 % of payments. The script reused one customer per virtual user, and the velocity rule correctly blocked the repeats. This shows the thresholds catch problems the latency numbers alone would hide.

## Alternatives
| Option | Trade-off |
|---|---|
| Gatling or JMeter | Mature; JVM or XML scripting, heavier to run in CI |
| Locust | Python; lower single-machine throughput for 1,000/s and weaker threshold semantics |
| Closed-model load (fixed virtual users) | Simpler; a slower system silently receives less load (coordinated omission) |
| **k6 with arrival-rate scenarios and thresholds as requirements** | One small script; JavaScript rather than the project's Java |

## Consequences
- **Not yet proven:** 1,000 payments/s has not been measured. It needs the AWS deployment (ADR-028) with its autoscaling, run from a separate load generator. The `peak` and `spike` profiles are ready for that run.
- **Tuning:** the baseline suggests the database connection pool (20) and CPU saturate first. Revisit pool size, task sizing and the ADR-027 alert thresholds with the numbers from AWS.
- **Scope:** mock PSPs answer instantly, so these tests measure the gateway's own overhead, which is what NFR-2 targets. Real PSP latency is covered by provider latency alerts, not by load tests.
