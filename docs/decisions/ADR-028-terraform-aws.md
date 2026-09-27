# ADR-028: Terraform for the AWS reference deployment

**Status:** Accepted (2026-09-27)

## Context
ADR-010 chose AWS: ECS Fargate and Aurora PostgreSQL in Mumbai, a warm standby in Hyderabad, WAF, Secrets Manager and KMS, and Terraform. Architecture §8 and §10 describe the security controls and DR the deployment must provide, and ADR-022 to ADR-027 added requirements the infrastructure has to meet:
- verified TLS to the database, and separate migrator and app roles;
- a PSP webhook source allowlist and restricted admin access at the edge;
- data keys delivered from Secrets Manager;
- the same alert rules locally and in AWS.

None of this existed as code. No AWS account or credentials are available to this project, so the code must be verifiable without one.

## Decision
- **Layout:** `infra/terraform/modules/`:
  - `network`: 3 AZs, NAT with Elastic IPs, isolated database subnets, flow logs, VPC endpoints, and a default security group with no rules.
  - `kms`: `data` and `telemetry` keys per region, with rotation, and key policies scoped by service and account.
  - `waf`, `aurora`, `ecs-service`, `observability`.
  - `region`: composes one region. `environments/prod` instantiates it twice, for Mumbai and Hyderabad, so the standby cannot drift from the primary.
- **Edge:**
  - The ALB uses `ELBSecurityPolicy-TLS13-1-2-2021-06`, redirects HTTP to HTTPS, drops invalid headers, and uses strictest desync mitigation.
  - The WAF blocks `/actuator`. `/v1/webhooks/providers/*` is accepted only from the PSPs' published ranges, the same ranges the application enforces per provider (ADR-022). `/admin/` is accepted only from operator networks, and only from India.
  - Also on the WAF: a per-IP rate limit (PSP webhooks exempt), AWS managed rule groups, and logs that redact `Authorization` and `Cookie`.
- **Compute:**
  - `api` and `worker` services have separate execution and task roles. Each execution role reads only its own secrets.
  - Root filesystems are read-only, with only `/tmp` writable. ECS Exec is off, and there are no public IPs.
  - The deployment circuit breaker rolls back failed deploys. Services autoscale on CPU.
  - The migration task definition gets only the migrator's secret (ADR-026). Every task connects with `sslmode=verify-full`.
  - An ADOT sidecar scrapes the management port into Amazon Managed Prometheus and sends traces to X-Ray.
- **Data:**
  - Aurora PostgreSQL (latest 17.x, resolved at plan) is a Global Database, KMS-encrypted, with `rds.force_ssl`, 35-day PITR, deletion protection and Performance Insights.
  - The master password is managed by RDS in Secrets Manager.
  - The `gateway_app` and `gateway_migrator` passwords come from ephemeral `random_password` and are written with write-only `secret_string_wo`, so no password is ever in Terraform state.
  - The data keys (`SPRING_APPLICATION_JSON`, ADR-025) are set out of band. Terraform creates only the secret.
  - All secrets replicate only to the standby region (NFR-15).
- **Residency and safety checks in code:**
  - Variables reject regions outside India, a standby in the primary region, mutable image tags (`latest`), and empty PSP ranges.
  - Providers pin `allowed_account_ids`.
  - ECR is immutable, scanned on push, KMS-encrypted and replicated to the standby region.
- **Alerting:**
  - AMP loads the same rule file as the local stack (ADR-027). Alertmanager routes `critical` to a paging topic and the rest to a ticket topic.
  - Two CloudWatch alarms cover what Prometheus cannot see in AWS:
    - no healthy API task behind a load balancer: a sidecar dies with its task, so `up == 0` never arrives;
    - global replication lag above the 1-minute RPO.
  - Topic policies admit only the workspace and this account's CloudWatch alarms. Region failover is a runbook ([docs/runbooks.md](../runbooks.md#region-failover)).
- **State:** the S3 backend with native lock files (`use_lockfile`) replaces the DynamoDB table named in ADR-010, which Terraform has deprecated. The bucket is versioned and KMS-encrypted, and is configured through `backend.hcl`.
- **Verification without AWS:** CI runs, for every module:
  - `terraform fmt`, `validate` and `terraform test` against mocked providers. There are 21 runs across 8 suites, covering WAF rules, TLS policy, IAM scoping, secret separation, encryption, alarms and input validation.
  - Trivy's misconfiguration scan at MEDIUM and above. Every inline exception carries its reason.

## Alternatives
| Option | Trade-off |
|---|---|
| Hand-built console setup | Fast once; not reviewable, repeatable or testable, and the standby drifts |
| AWS CDK or CloudFormation | Native to AWS; ADR-010 already chose Terraform, and its test framework mocks providers without an account |
| Community modules (terraform-aws-modules) | Less code; large surfaces to review, and the controls this design needs (WAF paths, secret separation) still need custom code |
| Passwords in Terraform state (`random_password` resource) | Simpler; state readers would get database credentials |
| **Own small modules, one region module used twice, ephemeral secrets, mocked tests and Trivy in CI** | More code to maintain; every control is visible, tested and identical in both regions |

## Consequences
- **Never applied:** nothing here has been applied to a real account. Mocked tests prove configuration and wiring, not AWS behaviour such as quotas or engine-version availability. The first apply should be to a sandbox account; add its findings to the tests.
- **One-time steps outside Terraform (outputs list them):**
  - run `deploy/db/roles.sql` with the RDS-managed master secret;
  - put the data keys into the `app-config` secret;
  - subscribe the paging tool and ticket queue to the alert topics;
  - give PSPs the NAT egress IPs of both regions.
- **Rotating database passwords:** bump `db_password_version`, apply, then update the role passwords with the same values.
- **Failover uses the CLI:** Terraform state lives in the primary region, so the procedure uses the AWS CLI, not Terraform.
- **Costs:** NAT gateways in 3 AZs, interface endpoints and a second region carry fixed costs even when idle; a non-prod environment would use one NAT and Aurora Serverless v2 (ADR-010).
