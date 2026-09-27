# ADR-010: AWS reference deployment — ECS Fargate + Aurora PostgreSQL, Mumbai / Hyderabad, Terraform

**Status:** Accepted (2026-09-26). Implemented in [ADR-028](ADR-028-terraform-aws.md), where state locking uses S3 lock files instead of DynamoDB.

## Context
RBI data localization requires payment data (including backups and DR) to stay in India. Targets: 99.95% availability, RPO 0 in-region / ≤ 1 min cross-region, RTO ≤ 30 min. The team is small, and V1 is deployed on demand.

## Decision
- **AWS**: ap-south-1 (Mumbai) primary across 3 AZs, and ap-south-2 (Hyderabad) as warm standby.
- **Compute:** ECS on Fargate with `api` and `worker` services (same image), behind WAF + ALB, in private subnets. NAT gateways with Elastic IPs give static egress for PSP allowlists.
- **Data:** Aurora PostgreSQL (Multi-AZ writer + reader), with Aurora Global Database to Hyderabad.
- **Secrets:** Secrets Manager + KMS. **Observability:** ADOT → Amazon Managed Prometheus, X-Ray, CloudWatch Logs.
- **IaC:** Terraform, with state in S3 and DynamoDB locking.

## Alternatives
| Option | Trade-off |
|---|---|
| GCP (Mumbai + Delhi) / Azure (Pune, Chennai, Mumbai) | Both viable and both have ≥ 2 Indian regions; AWS chosen for fintech ecosystem depth and managed Global DB failover |
| EKS (Kubernetes) | Flexible and portable; the control plane, upgrades, and add-ons are unnecessary ops for two services |
| RDS PostgreSQL Multi-AZ + cross-region replica | Cheaper; slower failover and manual DR promotion |
| Single-region only | Cheaper; fails the regional DR requirement |

## Consequences
- The container image is cloud-agnostic, so a move to EKS or another cloud needs only new IaC.
- Cost scales down for on-demand demos: Hyderabad runs at minimal capacity, and Aurora can use Serverless v2 for non-prod.
- DR runbook and quarterly drills are required to claim the RTO.
