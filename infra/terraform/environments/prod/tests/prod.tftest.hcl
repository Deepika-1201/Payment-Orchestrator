# terraform test in environments/prod (ADR-028): mocked apply of both regions, no AWS credentials. Module internals
# are tested in each module's tests/; this file covers what the environment itself decides.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

mock_provider "aws" {
  alias  = "dr"
  source = "../../tests/mocks/ap-south-2"
}

# The real random provider generates the ephemeral passwords: mocks cannot model ephemeral resources, and it needs no
# credentials.

variables {
  account_id = "123456789012"
  image_tag  = "901174a"
  api_domain = "api.pay.example.com"
  certificate_arns = {
    primary = "arn:aws:acm:ap-south-1:123456789012:certificate/primary"
    dr      = "arn:aws:acm:ap-south-2:123456789012:certificate/dr"
  }
  psp_webhook_sources = {
    RAZORPAY = ["192.0.2.0/28"]
    CASHFREE = ["198.51.100.0/28"]
  }
  admin_ipv4_cidrs = ["203.0.113.0/24"]
}

run "production_stack" {
  command = apply

  assert {
    condition     = local.app_environment["PG_WEBHOOKS_INBOUND_ALLOWEDSOURCES_RAZORPAY"] == "192.0.2.0/28" && local.app_environment["PG_WEBHOOKS_INBOUND_ALLOWEDSOURCES_CASHFREE"] == "198.51.100.0/28" && toset(local.psp_ipv4_cidrs) == toset(["192.0.2.0/28", "198.51.100.0/28"])
    error_message = "The same PSP ranges feed the WAF and the application's per-provider allowlist (ADR-022)."
  }

  assert {
    condition     = local.app_environment["PG_CHECKOUT_BASE_URL"] == "https://api.pay.example.com" && local.app_environment["PG_API_KEY_MODE"] == "live"
    error_message = "The prod profile gets an HTTPS checkout URL and live API keys."
  }

  assert {
    condition     = alltrue([for secret in [aws_secretsmanager_secret.db_app, aws_secretsmanager_secret.db_migrator, aws_secretsmanager_secret.app_config] : [for replica in secret.replica : replica.region] == ["ap-south-2"]])
    error_message = "Secrets replicate to the standby region only (NFR-15)."
  }

  assert {
    condition     = alltrue([for arn in values(output.standby_secret_arns) : strcontains(arn, ":ap-south-2:")])
    error_message = "Standby-region tasks read the replicas in their own region."
  }

  assert {
    condition     = aws_ecr_repository.gateway.image_tag_mutability == "IMMUTABLE" && aws_ecr_repository.gateway.image_scanning_configuration[0].scan_on_push && aws_ecr_repository.gateway.encryption_configuration[0].encryption_type == "KMS"
    error_message = "Images are immutable, scanned on push and KMS-encrypted."
  }

  assert {
    condition     = aws_rds_global_cluster.this.storage_encrypted && aws_rds_global_cluster.this.deletion_protection
    error_message = "The global database is encrypted and protected from deletion."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.global_replication_lag.metric_name == "AuroraGlobalDBReplicationLag" && aws_cloudwatch_metric_alarm.global_replication_lag.threshold == 60000 && aws_cloudwatch_metric_alarm.global_replication_lag.treat_missing_data == "breaching"
    error_message = "Cross-region lag above the 1-minute RPO (NFR-4) raises a critical alarm, as does a missing metric."
  }

  assert {
    condition     = length(aws_route53_record.api) == 0
    error_message = "No DNS records without a hosted zone."
  }

  assert {
    condition     = output.migration_task.task_definition != null && length(output.migration_task.subnets) == 3
    error_message = "The outputs carry what run-task needs for the migration."
  }
}

run "failover_dns_follows_load_balancer_health" {
  command = apply

  variables {
    hosted_zone_id = "Z0123456789EXAMPLE"
  }

  assert {
    condition     = { for key, record in aws_route53_record.api : key => record.failover_routing_policy[0].type } == { primary = "PRIMARY", standby = "SECONDARY" }
    error_message = "The primary region answers until its load balancer is unhealthy, then the standby does."
  }

  assert {
    condition     = alltrue([for record in aws_route53_record.api : record.alias[0].evaluate_target_health])
    error_message = "Failover follows target health."
  }
}

run "regions_outside_india_are_rejected" {
  command = plan

  variables {
    dr_region = "us-east-1"
  }

  expect_failures = [var.dr_region]
}

run "a_standby_in_the_primary_region_is_rejected" {
  command = plan

  variables {
    dr_region = "ap-south-1"
  }

  expect_failures = [var.dr_region]
}

run "empty_psp_ranges_are_rejected" {
  command = plan

  variables {
    psp_webhook_sources = { RAZORPAY = [] }
  }

  expect_failures = [var.psp_webhook_sources]
}

run "mutable_image_tags_are_rejected" {
  command = plan

  variables {
    image_tag = "latest"
  }

  expect_failures = [var.image_tag]
}
