# Production: ap-south-1 (Mumbai) primary and ap-south-2 (Hyderabad) warm standby (ADR-010, ADR-028).

data "aws_caller_identity" "current" {}

locals {
  name        = "pg-prod"
  region_code = { "ap-south-1" = "mum", "ap-south-2" = "hyd" }

  tags = {
    application         = "payment-gateway"
    environment         = "prod"
    managed-by          = "terraform"
    data-classification = "payment"
  }

  psp_ipv4_cidrs = distinct(flatten(values(var.psp_webhook_sources)))

  # Non-secret settings. The same PSP ranges feed the WAF and the application's per-provider allowlist (ADR-022).
  app_environment = merge(
    {
      PG_CHECKOUT_BASE_URL = "https://${var.api_domain}"
      PG_API_KEY_MODE      = "live"
    },
    { for provider, cidrs in var.psp_webhook_sources : "PG_WEBHOOKS_INBOUND_ALLOWEDSOURCES_${upper(provider)}" => join(",", cidrs) },
    var.oidc == null ? {} : {
      PG_SECURITY_OIDC_ISSUER   = var.oidc.issuer
      PG_SECURITY_OIDC_JWKSURI  = var.oidc.jwks_uri
      PG_SECURITY_OIDC_AUDIENCE = var.oidc.audience
    },
  )
}

module "kms_primary" {
  source = "../../modules/kms"
  name   = "${local.name}-${local.region_code[var.primary_region]}"
  tags   = local.tags
}

module "kms_dr" {
  source    = "../../modules/kms"
  providers = { aws = aws.dr }
  name      = "${local.name}-${local.region_code[var.dr_region]}"
  tags      = local.tags
}

resource "aws_ecr_repository" "gateway" {
  name                 = "payment-gateway"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = module.kms_primary.data_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "gateway" {
  repository = aws_ecr_repository.gateway.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 100 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 100 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_ecr_replication_configuration" "dr" {
  replication_configuration {
    rule {
      destination {
        region      = var.dr_region
        registry_id = data.aws_caller_identity.current.account_id
      }
      repository_filter {
        filter      = aws_ecr_repository.gateway.name
        filter_type = "PREFIX_MATCH"
      }
    }
  }
}

# Database role credentials (ADR-026). Passwords are ephemeral and written write-only, so they never reach the state.
ephemeral "random_password" "db_app" {
  length  = 40
  special = false
}

ephemeral "random_password" "db_migrator" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "db_app" {
  name                    = "${local.name}/db/gateway-app"
  description             = "gateway_app database role: data access only (ADR-026)"
  kms_key_id              = module.kms_primary.data_key_arn
  recovery_window_in_days = 30

  replica {
    region     = var.dr_region
    kms_key_id = module.kms_dr.data_key_arn
  }
}

resource "aws_secretsmanager_secret_version" "db_app" {
  secret_id                = aws_secretsmanager_secret.db_app.id
  secret_string_wo         = jsonencode({ username = "gateway_app", password = ephemeral.random_password.db_app.result })
  secret_string_wo_version = var.db_password_version
}

resource "aws_secretsmanager_secret" "db_migrator" {
  name                    = "${local.name}/db/gateway-migrator"
  description             = "gateway_migrator database role: schema owner, used only by the migration task (ADR-026)"
  kms_key_id              = module.kms_primary.data_key_arn
  recovery_window_in_days = 30

  replica {
    region     = var.dr_region
    kms_key_id = module.kms_dr.data_key_arn
  }
}

resource "aws_secretsmanager_secret_version" "db_migrator" {
  secret_id                = aws_secretsmanager_secret.db_migrator.id
  secret_string_wo         = jsonencode({ username = "gateway_migrator", password = ephemeral.random_password.db_migrator.result })
  secret_string_wo_version = var.db_password_version
}

# SPRING_APPLICATION_JSON with pg.security data keys (ADR-025). Terraform never sees the value: set it out of band.
resource "aws_secretsmanager_secret" "app_config" {
  name                    = "${local.name}/app-config"
  description             = "Gateway secrets as SPRING_APPLICATION_JSON (data keys); value set out of band (ADR-028)"
  kms_key_id              = module.kms_primary.data_key_arn
  recovery_window_in_days = 30

  replica {
    region     = var.dr_region
    kms_key_id = module.kms_dr.data_key_arn
  }
}

locals {
  # Replicas keep the name and suffix; only the region in the ARN changes.
  dr_secret_arns = {
    for key, secret in {
      db_app      = aws_secretsmanager_secret.db_app
      db_migrator = aws_secretsmanager_secret.db_migrator
      app_config  = aws_secretsmanager_secret.app_config
    } : key => replace(secret.arn, ":${var.primary_region}:", ":${var.dr_region}:")
  }
}

data "aws_rds_engine_version" "postgresql" {
  engine  = "aurora-postgresql"
  version = var.postgres_major_version
  latest  = true
}

resource "aws_rds_global_cluster" "this" {
  global_cluster_identifier = local.name
  engine                    = "aurora-postgresql"
  engine_version            = data.aws_rds_engine_version.postgresql.version_actual
  database_name             = "payments"
  storage_encrypted         = true
  deletion_protection       = true

  lifecycle {
    ignore_changes = [engine_version]
  }
}

module "observability" {
  source      = "../../modules/observability"
  name        = local.name
  rules_file  = "${path.module}/../../../../deploy/observability/prometheus/rules/payment-gateway.yml"
  kms_key_arn = module.kms_primary.telemetry_key_arn
  tags        = local.tags
}

locals {
  amp = {
    workspace_arn    = module.observability.workspace_arn
    region           = var.primary_region
    remote_write_url = module.observability.remote_write_url
  }
}

module "primary" {
  source = "../../modules/region"

  name                      = "${local.name}-${local.region_code[var.primary_region]}"
  cidr                      = "10.20.0.0/16"
  image                     = "${aws_ecr_repository.gateway.repository_url}:${var.image_tag}"
  certificate_arn           = var.certificate_arns.primary
  db_role                   = "primary"
  global_cluster_identifier = aws_rds_global_cluster.this.id
  engine_version            = aws_rds_global_cluster.this.engine_version
  db_instance_class         = var.db_instance_class
  db_instance_count         = 2
  api_capacity              = { desired = 3, min = 3, max = 30 }
  worker_capacity           = { desired = 2, min = 2, max = 10 }
  psp_webhook_ipv4_cidrs    = local.psp_ipv4_cidrs
  admin_ipv4_cidrs          = var.admin_ipv4_cidrs
  app_environment           = local.app_environment
  db_app_secret_arn         = aws_secretsmanager_secret.db_app.arn
  db_migrator_secret_arn    = aws_secretsmanager_secret.db_migrator.arn
  app_config_secret_arn     = aws_secretsmanager_secret.app_config.arn
  data_kms_key_arn          = module.kms_primary.data_key_arn
  telemetry_kms_key_arn     = module.kms_primary.telemetry_key_arn
  amp                       = local.amp
  critical_alarm_topic_arn  = module.observability.alert_topic_arns["critical"]
  tags                      = local.tags
}

# Warm standby: one database instance, one API task, no workers (the database is read-only until failover).
module "dr" {
  source    = "../../modules/region"
  providers = { aws = aws.dr }

  name                      = "${local.name}-${local.region_code[var.dr_region]}"
  cidr                      = "10.30.0.0/16"
  image                     = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.dr_region}.amazonaws.com/${aws_ecr_repository.gateway.name}:${var.image_tag}"
  certificate_arn           = var.certificate_arns.dr
  db_role                   = "secondary"
  global_cluster_identifier = aws_rds_global_cluster.this.id
  engine_version            = aws_rds_global_cluster.this.engine_version
  db_instance_class         = var.db_instance_class
  db_instance_count         = 1
  api_capacity              = { desired = 1, min = 1, max = 30 }
  worker_capacity           = { desired = 0, min = 0, max = 10 }
  psp_webhook_ipv4_cidrs    = local.psp_ipv4_cidrs
  admin_ipv4_cidrs          = var.admin_ipv4_cidrs
  app_environment           = local.app_environment
  db_app_secret_arn         = local.dr_secret_arns.db_app
  db_migrator_secret_arn    = local.dr_secret_arns.db_migrator
  app_config_secret_arn     = local.dr_secret_arns.app_config
  data_kms_key_arn          = module.kms_dr.data_key_arn
  telemetry_kms_key_arn     = module.kms_dr.telemetry_key_arn
  amp                       = local.amp
  critical_alarm_topic_arn  = aws_sns_topic.dr_critical.arn
  tags                      = local.tags

  depends_on = [module.primary]
}

# Cross-region RPO (NFR-4: at most 1 minute). The metric lives in the standby region, so the alarm and topic do too.
resource "aws_sns_topic" "dr_critical" {
  provider          = aws.dr
  name              = "${local.name}-${local.region_code[var.dr_region]}-alerts-critical"
  kms_master_key_id = module.kms_dr.telemetry_key_arn
}

resource "aws_cloudwatch_metric_alarm" "global_replication_lag" {
  provider            = aws.dr
  alarm_name          = "${local.name}-aurora-global-replication-lag"
  alarm_description   = "Cross-region replication lag above the 1-minute RPO (NFR-4); see docs/runbooks.md#auroraglobalreplicationlag"
  namespace           = "AWS/RDS"
  metric_name         = "AuroraGlobalDBReplicationLag"
  dimensions          = { DBClusterIdentifier = module.dr.db_cluster_id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 60000
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.dr_critical.arn]
  ok_actions          = [aws_sns_topic.dr_critical.arn]
}

# Failover DNS: Route 53 follows the load balancers' target health.
resource "aws_route53_record" "api" {
  for_each = var.hosted_zone_id == null ? {} : {
    primary = { role = "PRIMARY", dns_name = module.primary.alb_dns_name, zone_id = module.primary.alb_zone_id }
    standby = { role = "SECONDARY", dns_name = module.dr.alb_dns_name, zone_id = module.dr.alb_zone_id }
  }

  zone_id        = var.hosted_zone_id
  name           = var.api_domain
  type           = "A"
  set_identifier = each.key

  failover_routing_policy {
    type = each.value.role
  }

  alias {
    name                   = each.value.dns_name
    zone_id                = each.value.zone_id
    evaluate_target_health = true
  }
}
