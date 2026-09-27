# terraform test in modules/region (ADR-028): mocked apply of one region, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name                      = "pg-prod-mum"
  cidr                      = "10.20.0.0/16"
  image                     = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/payment-gateway:901174a"
  certificate_arn           = "arn:aws:acm:ap-south-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
  db_role                   = "primary"
  global_cluster_identifier = "pg-prod"
  engine_version            = "17.6"
  api_capacity              = { desired = 3, min = 3, max = 30 }
  worker_capacity           = { desired = 2, min = 2, max = 10 }
  psp_webhook_ipv4_cidrs    = ["192.0.2.0/28"]
  admin_ipv4_cidrs          = ["203.0.113.0/24"]
  app_environment           = { PG_CHECKOUT_BASE_URL = "https://api.pay.example.com" }
  db_app_secret_arn         = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:pg-prod/db/gateway-app-AbCdEf"
  db_migrator_secret_arn    = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:pg-prod/db/gateway-migrator-AbCdEf"
  app_config_secret_arn     = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:pg-prod/app-config-AbCdEf"
  data_kms_key_arn          = "arn:aws:kms:ap-south-1:123456789012:key/11111111-1111-1111-1111-111111111111"
  telemetry_kms_key_arn     = "arn:aws:kms:ap-south-1:123456789012:key/33333333-3333-3333-3333-333333333333"
  amp = {
    workspace_arn    = "arn:aws:aps:ap-south-1:123456789012:workspace/ws-example"
    region           = "ap-south-1"
    remote_write_url = "https://aps-workspaces.ap-south-1.amazonaws.com/workspaces/ws-example/api/v1/remote_write"
  }
  critical_alarm_topic_arn = "arn:aws:sns:ap-south-1:123456789012:pg-prod-alerts-critical"
}

run "edge_terminates_tls_and_health_checks_the_management_port" {
  command = apply

  assert {
    condition     = aws_lb_listener.https.ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06" && aws_lb_listener.https.certificate_arn == var.certificate_arn
    error_message = "HTTPS uses the TLS 1.3 / 1.2 policy (NFR-9)."
  }

  assert {
    condition     = aws_lb_listener.http.default_action[0].type == "redirect" && aws_lb_listener.http.default_action[0].redirect[0].protocol == "HTTPS" && aws_lb_listener.http.default_action[0].redirect[0].status_code == "HTTP_301"
    error_message = "Port 80 only redirects to HTTPS."
  }

  assert {
    condition     = aws_lb.this.drop_invalid_header_fields && aws_lb.this.desync_mitigation_mode == "strictest" && aws_lb.this.enable_deletion_protection
    error_message = "The load balancer drops invalid headers, uses strictest desync mitigation and is protected from deletion."
  }

  assert {
    condition     = aws_lb_target_group.api.health_check[0].port == "8081" && aws_lb_target_group.api.health_check[0].path == "/actuator/health/readiness"
    error_message = "Health checks use the management port's readiness probe."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.tasks_from_alb.referenced_security_group_id == aws_security_group.alb.id && aws_vpc_security_group_egress_rule.tasks_database.referenced_security_group_id == module.aurora.security_group_id
    error_message = "Tasks accept traffic only from the load balancer and reach only their own database on 5432."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.no_healthy_api_task.metric_name == "HealthyHostCount" && aws_cloudwatch_metric_alarm.no_healthy_api_task.threshold == 1 && aws_cloudwatch_metric_alarm.no_healthy_api_task.comparison_operator == "LessThanThreshold" && aws_cloudwatch_metric_alarm.no_healthy_api_task.treat_missing_data == "breaching" && aws_cloudwatch_metric_alarm.no_healthy_api_task.alarm_actions == toset([var.critical_alarm_topic_arn])
    error_message = "Losing every healthy API task pages, as does losing the metric: in AWS a dead task cannot report up == 0."
  }
}

run "services_get_only_the_secrets_they_need" {
  command = apply

  assert {
    condition     = module.migrate.secret_names == tolist(["DB_MIGRATION_PASSWORD"]) && module.migrate.service_name == null
    error_message = "The migration task is run on demand and receives the migrator's password only (ADR-026)."
  }

  assert {
    condition     = module.api.secret_names == tolist(["DB_PASSWORD", "SPRING_APPLICATION_JSON"]) && module.worker.secret_names == tolist(["DB_PASSWORD", "SPRING_APPLICATION_JSON"])
    error_message = "API and worker tasks get the app role's password and the data keys, never the migrator's credentials."
  }

  assert {
    condition     = alltrue([for service in [module.api, module.worker, module.migrate] : strcontains(service.environment.DB_URL, "sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem")])
    error_message = "Every database connection verifies the server certificate (ADR-026)."
  }

  assert {
    condition     = module.api.environment.PG_WORKERS_ENABLED == "false" && module.worker.environment.PG_WORKERS_ENABLED == "true" && module.migrate.environment.PG_MIGRATE_ONLY == "true"
    error_message = "API tasks serve traffic, worker tasks run jobs, the migration task migrates and exits."
  }

  assert {
    condition     = alltrue([for service in [module.api, module.worker] : service.environment.SPRING_PROFILES_ACTIVE == "prod" && service.environment.DB_USER == "gateway_app" && service.environment.DB_MIGRATE_ON_START == "false"])
    error_message = "Services run the prod profile as the app role and never migrate."
  }

  assert {
    condition     = module.api.environment.OTLP_TRACING_ENABLED == "true" && !contains(keys(module.migrate.environment), "OTLP_TRACING_ENABLED")
    error_message = "Services send traces to their ADOT sidecar; the short-lived migration task has none."
  }
}
