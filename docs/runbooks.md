# Runbooks

Operational response for every alert in [deploy/observability/prometheus/rules/payment-gateway.yml](../deploy/observability/prometheus/rules/payment-gateway.yml) ([ADR-027](decisions/ADR-027-observability-slos-and-alerts.md)), and for the CloudWatch alarms Terraform creates in AWS ([ADR-028](decisions/ADR-028-terraform-aws.md)). Each heading is an alert or alarm name, which is what its `runbook_url` or alarm description links to.

- **Severity:** `critical` pages the on-call engineer at any hour. `warning` opens a ticket for business hours.
- **Dashboard:** Grafana, *Payment Gateway - Overview* (`deploy/observability/grafana/dashboards/payment-gateway.json`).
- **Correlation:** every log line and span carries `request_id`, and where known `merchant_id`, `payment_id`, attempt id and `provider` (NFR-10). Start from an id in the alert or the dashboard, then search logs and traces for it.
- **Admin API:** examples use `$ADMIN` as `https://<host>/admin/v1` with an SSO bearer token (ADR-023). Writes need the permission shown and are audited.

## Service level objectives

### ApiErrorBudgetBurnFast
Payment API or PSP webhook requests are failing with 5xx fast enough to spend 2 % of the monthly budget in an hour (or 5 % in six hours).
- Check the *API requests per second by status* panel and the error logs for the dominant exception and endpoint.
- Check dependencies: the database (*Database connections*), and PSP health with `GET $ADMIN/providers/health`.
- If a deploy started it, roll back: redeploy the previous task definition. The ECS circuit breaker may already have done this.
- If one PSP causes it, shift traffic away with `PUT $ADMIN/routing-rules/{id}` (`routing_write`).

### ApiErrorBudgetBurnSlow
A low but steady 5xx rate will exhaust the monthly budget if nothing changes.
- Find the endpoint and exception in the *API requests per second by status* panel and the error logs; fix it in the next release.
- Pause risky changes (feature launches, migrations) until the burn rate is back under 1.

### ApiLatencyAboveSlo
p99 of API requests that never call a PSP (reads and payment creation) is above 150 ms (NFR-2).
- Check *Database connections* for waiting requests and *Process CPU* for saturation. Scale out the API service if CPU-bound.
- Look at slow traces for `GET /v1/payments/{paymentId}` and `POST /v1/payments` in Tempo or X-Ray. Slow SQL points to a missing index or lock contention.

### WebhookAckLatencyAboveSlo
PSPs wait more than 200 ms at p99 for webhook acknowledgements (NFR-2). PSPs retry, and some disable endpoints that stay slow.
- Webhooks are only stored before the acknowledgement; slowness means the insert or signature check is slow. Check *Database connections* and database latency first.
- Scale out the API service if CPU-bound.

## Payment service providers

### ProviderSuccessRateBelowBaseline
A PSP's success ratio over 30 minutes is more than 10 points below its daily baseline, with meaningful volume.
- Compare methods and banks in *Attempt transitions per second* and the decline codes in logs; the PSP status page often explains it.
- Check `GET $ADMIN/providers/health`. If the PSP is degraded, shift traffic to another provider with `PUT $ADMIN/routing-rules/{id}` (`routing_write`), and record the change in the incident.
- Shift traffic back once the ratio recovers.

### ProviderLatencyHigh
p99 of calls to a PSP operation is above 3 s; the read timeout is 10 s.
- Slow PSPs push attempts toward timeouts and unknown outcomes. Watch *Attempts with unknown outcome*.
- Check the PSP status page, and our NAT gateway metrics in CloudWatch.
- Shift traffic with a routing rule if it persists.

### ProviderErrorsHigh
More than 5 % of calls to a PSP fail, time out, have credentials rejected, or are short-circuited by the open circuit breaker.
- *PSP call results per second* shows which result dominates:
  - `credentials_rejected`: a merchant's PSP account credentials were revoked or rotated. Update them with `PUT $ADMIN/merchants/{id}/provider-accounts/{provider}` (`merchants_write`), or disable the account.
  - `timeout`, `unavailable`: PSP or network trouble. Routing fails over to healthy providers where the merchant has one.
  - `circuit_open`: the breaker is protecting the PSP; it probes again automatically.

### PaymentOutcomeUnknownTooLong
An attempt has had an unknown outcome (a PSP timeout or ambiguous response) for over an hour. The customer may have been charged.
- Status checks keep retrying automatically. See *Status checks per second by outcome*: many `error` or `not_found` results mean the PSP status API is failing.
- Find the attempts with `GET $ADMIN/reviews?kind=attempt` and ask the PSP for their status with the provider reference.
- Resolve each with `POST $ADMIN/reviews/attempts/{id}/resolve` (`operations_write`) using the PSP's answer. Never guess a success.

## Webhooks

### MerchantWebhookDead
A merchant webhook delivery exhausted its retries. The merchant has not heard about an event.
- Find it in the logs (`Webhook delivery ... is DEAD`); the error shows the HTTP status or connection failure.
- Contact the merchant if their endpoint is failing. Once it is fixed, list the resource's deliveries with `GET $ADMIN/webhook-deliveries?resource_id=...` and replay with `POST $ADMIN/webhook-deliveries/{id}/replay` (`operations_write`).

### MerchantWebhookBacklog
Due merchant deliveries have waited more than 5 minutes: the outbox is not draining, and every merchant is affected.
- Check the worker service is running (`PG_WORKERS_ENABLED=true` tasks) and look for errors in the worker logs.
- Check *Database connections*. The worker claims deliveries with `FOR UPDATE SKIP LOCKED`, so long transactions or locks stall it.
- Scale out the worker service if it is healthy but behind.

### ProviderWebhooksRejected
Many webhooks claiming to come from a PSP failed signature verification or came from outside the PSP's source allowlist (ADR-022).
- `invalid` after a PSP secret rotation: update the webhook secret on the provider account. Many `invalid` requests from unknown sources may be forgery; the rejections are the protection working.
- `source_rejected` from a legitimate PSP means its egress IPs changed. Confirm against the PSP's published list before updating `pg.webhooks.inbound.allowed-sources` and the WAF IP set.
- Status checks and reconciliation still resolve payments while webhooks are rejected.

### ProviderWebhookRetries
Stored PSP webhooks keep failing to process, so their updates are delayed.
- The logs (`Processing webhook ... failed`) show the exception. It is usually an unexpected payload after a PSP API change, or a database problem.
- Events are kept and retried. Fix the cause and they are processed on the next retry.

## Money safety and operations

### ProviderAmountMismatch
A PSP reported a different amount for an attempt than we sent. The update was not applied, and the attempt is flagged for review.
- Treat it as a potential incident: a PSP bug, a misrouted reference, or tampering.
- Find the attempt with `GET $ADMIN/reviews?kind=attempt`, compare against the PSP dashboard, and escalate to the PSP.
- Resolve only with confirmed evidence (`POST $ADMIN/reviews/attempts/{id}/resolve`). Correct the ledger through a maker-checker adjustment if needed (ADR-024).

### ProviderEvidenceConflict
PSP evidence contradicted a final state (for example a success after a failure, or a dispute event out of order). It was ignored and flagged for review.
- Work the review queue: `GET $ADMIN/reviews`. Late successes follow the merchant's policy (auto-refund by default, FR-P8); anything else needs a decision.

### ReconciliationExceptionsOverdue
Reconciliation exceptions are open past their SLA (ADR-017).
- `GET $ADMIN/reconciliation/exceptions?overdue=true` lists them. Assign an owner with `POST $ADMIN/reconciliation/exceptions/{id}/assign` (`finance_write`).
- Resolve them with evidence: `POST $ADMIN/reconciliation/exceptions/{id}/resolve`, and a ledger adjustment if money must move.

### ReviewQueueBacklog
More than 25 items of one kind have waited for manual review for an hour.
- `GET $ADMIN/reviews?kind=...` lists them oldest first. A sudden rise usually has one cause: one provider's conflicts, a failing risk rule, or a dispute batch. Fix the cause before working the queue.

## Security

### AdminAccessDeniedSpike
More than 20 admin requests were denied for missing permissions in 15 minutes.
- The logs show who was denied what (`Admin ... denied ...`).
- If it is one operator after a role change, fix the SSO group mapping.
- If it is unexpected, treat it as probing: check the audit log and SSO sign-ins, and revoke sessions if needed.

### RiskRuleErrors
A risk rule is throwing errors. Affected payments go to review instead of being allowed (fail safe).
- The logs name the rule and the exception. For the external risk rule, check the provider's status and our timeout.
- Watch *Open manual reviews by kind* for growth.

## Runtime

### GatewayInstanceDown
Prometheus cannot scrape an instance.
- ECS replaces unhealthy tasks automatically. Check the service events for crash loops, and the task's last logs for the cause, such as a startup guard refusing unsafe configuration (ADR-022).
- If every instance is down, treat it as an outage: roll back the last deploy.

### DatabasePoolSaturated
Requests are waiting for database connections.
- Look for slow queries and long transactions (Performance Insights), then for traffic spikes.
- Scaling out tasks adds connections too. Check Aurora's connection headroom first; the app role has a limit of 400 (ADR-026).

### JvmHeapHigh
Heap use has stayed above 90 % for 10 minutes. Tasks exit on OutOfMemoryError and are replaced.
- Check whether it follows traffic (scale out) or grows steadily (a leak: capture a heap dump from one task before it restarts).

## AWS alarms (CloudWatch)

### NoHealthyApiTask
No API task in one region has passed its readiness check at the load balancer for 2 minutes, or the metric is missing. This is the AWS counterpart of `GatewayInstanceDown`: each ADOT sidecar scrapes its own task, so a dead task stops reporting instead of reporting `up == 0`.
- Check the API service's events and stopped tasks (`aws ecs describe-services`, `aws ecs describe-tasks` for the stopped reason). Common causes: failing readiness (the database), a startup guard refusing unsafe configuration (ADR-022), an unreadable secret, or an image pull error.
- A bad deploy is rolled back by the deployment circuit breaker. Otherwise, redeploy the previous task definition.
- If the database or the whole region is impaired, follow [Region failover](#region-failover).

### AuroraGlobalReplicationLag
The standby region is more than a minute behind the primary (the NFR-4 RPO), or the metric is missing.
- Check the global database in the RDS console. Lag usually follows write spikes or trouble between the regions.
- A missing metric can mean the secondary cluster is unhealthy. Check it first: failover depends on it.
- While the lag exceeds the RPO, an unplanned failover loses those writes. If the primary still serves traffic, fix the lag rather than failing over.

## Region failover
Moves the gateway from ap-south-1 (Mumbai) to ap-south-2 (Hyderabad) within the 30-minute RTO (ADR-010). Drill it every quarter. Terraform state lives in the primary region, so the steps use the AWS CLI.
1. **Decide.** The primary is impaired and not recovering: AWS Health Dashboard, `NoHealthyApiTask` in the primary, the database unavailable. Record the replication lag at that moment; an unplanned failover loses writes inside it.
2. **Promote the standby database.**
   - Primary still reachable (planned, no data loss): `aws rds switchover-global-cluster --global-cluster-identifier pg-prod --target-db-cluster-identifier <pg-prod-hyd cluster ARN>`.
   - Primary unreachable: `aws rds failover-global-cluster --global-cluster-identifier pg-prod --target-db-cluster-identifier <pg-prod-hyd cluster ARN> --allow-data-loss`.
   - The standby tasks already use their own cluster's endpoint, which accepts writes once promoted.
3. **Scale the standby.**
   - Raise the worker floor first, or target tracking scales it back to 0: `aws application-autoscaling register-scalable-target --service-namespace ecs --scalable-dimension ecs:service:DesiredCount --resource-id service/pg-prod-hyd/pg-prod-hyd-worker --min-capacity 2 --max-capacity 10`.
   - Do the same for the API (`pg-prod-hyd-api`, minimum 3).
   - Then `aws ecs update-service --cluster pg-prod-hyd --service pg-prod-hyd-api --force-new-deployment`, so that connection pools open against the writer.
4. **Traffic.**
   - Route 53 answers with the standby once the primary's load balancer has no healthy targets. Readiness includes the database, so this usually happens by itself.
   - PSPs already allowlist both regions' egress IPs (`terraform output psp_egress_ips`).
5. **Verify.** A payment succeeds end to end, `pg_webhook_deliveries_lag_seconds` drains, and the next reconciliation run is clean.
6. **Fail back.** Once the primary is healthy and caught up, run a planned switchover back to Mumbai. Restore the standby's capacity to the Terraform values, then run `terraform apply` to confirm there is no drift.
