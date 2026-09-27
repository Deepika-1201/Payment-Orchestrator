# One region of the gateway (ADR-010, ADR-028): network, edge, compute and the regional Aurora cluster.
# The primary and the standby region are two instances of this module, so they cannot drift apart.

module "network" {
  source           = "../network"
  name             = var.name
  cidr             = var.cidr
  logs_kms_key_arn = var.telemetry_kms_key_arn
  tags             = var.tags
}

resource "aws_security_group" "alb" {
  name_prefix = "${var.name}-alb-"
  description = "HTTPS from the internet to the load balancer; AWS WAF filters in front"
  vpc_id      = module.network.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-alb" })

  lifecycle {
    create_before_destroy = true
  }
}

#trivy:ignore:AVD-AWS-0107 A public payment API must accept HTTPS from any client; AWS WAF filters it.
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from clients"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

#trivy:ignore:AVD-AWS-0107 Port 80 only redirects to HTTPS.
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from clients, redirected to HTTPS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Application and health-check ports on the tasks"
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 8080
  to_port                      = 8081
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "tasks" {
  name_prefix = "${var.name}-tasks-"
  description = "Gateway API, worker and migration tasks"
  vpc_id      = module.network.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-tasks" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  security_group_id            = aws_security_group.tasks.id
  description                  = "Traffic and health checks from the load balancer"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8081
  ip_protocol                  = "tcp"
}

#trivy:ignore:AVD-AWS-0104 PSP APIs and merchant webhook endpoints are on the internet; egress leaves through the NAT Elastic IPs.
resource "aws_vpc_security_group_egress_rule" "tasks_https" {
  security_group_id = aws_security_group.tasks.id
  description       = "HTTPS to PSPs, merchant webhook endpoints and AWS APIs"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "tasks_database" {
  security_group_id            = aws_security_group.tasks.id
  description                  = "PostgreSQL"
  referenced_security_group_id = module.aurora.security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

#trivy:ignore:AVD-AWS-0053 The merchant API, PSP webhooks and hosted checkout are public by design.
resource "aws_lb" "this" {
  name                       = substr(var.name, 0, 32)
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = module.network.public_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
  enable_deletion_protection = true
  idle_timeout               = 60
  tags                       = var.tags
}

resource "aws_lb_target_group" "api" {
  name_prefix          = "api-"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = module.network.vpc_id
  deregistration_delay = 30
  tags                 = var.tags

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    port                = "8081"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  lifecycle {
    create_before_destroy = true
  }
}

# TLS 1.3 preferred, 1.2 for older clients (NFR-9).
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  tags              = var.tags

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  tags              = var.tags

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# The ADOT sidecar scrapes its own task, so a dead task stops reporting instead of reporting up == 0; the load balancer
# still sees it.
resource "aws_cloudwatch_metric_alarm" "no_healthy_api_task" {
  alarm_name          = "${var.name}-no-healthy-api-task"
  alarm_description   = "No API task passes its readiness check; see docs/runbooks.md#nohealthyapitask"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = aws_lb.this.arn_suffix, TargetGroup = aws_lb_target_group.api.arn_suffix }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.critical_alarm_topic_arn]
  ok_actions          = [var.critical_alarm_topic_arn]
  tags                = var.tags
}

module "waf" {
  source                 = "../waf"
  name                   = var.name
  alb_arn                = aws_lb.this.arn
  psp_webhook_ipv4_cidrs = var.psp_webhook_ipv4_cidrs
  psp_webhook_ipv6_cidrs = var.psp_webhook_ipv6_cidrs
  admin_ipv4_cidrs       = var.admin_ipv4_cidrs
  logs_kms_key_arn       = var.telemetry_kms_key_arn
  tags                   = var.tags
}

module "aurora" {
  source                    = "../aurora"
  name                      = var.name
  role                      = var.db_role
  global_cluster_identifier = var.global_cluster_identifier
  engine_version            = var.engine_version
  vpc_id                    = module.network.vpc_id
  subnet_ids                = module.network.database_subnet_ids
  client_security_group_ids = [aws_security_group.tasks.id]
  kms_key_arn               = var.data_kms_key_arn
  instance_class            = var.db_instance_class
  instance_count            = var.db_instance_count
  deletion_protection       = var.db_deletion_protection
  tags                      = var.tags
}

resource "aws_ecs_cluster" "this" {
  name = var.name
  tags = var.tags

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

locals {
  # The image carries the RDS CA bundle; verify-full checks the server certificate and host name (ADR-026).
  database_url = "jdbc:postgresql://${module.aurora.endpoint}:5432/payments?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem"

  app_environment = merge(var.app_environment, {
    SPRING_PROFILES_ACTIVE = "prod"
    DB_URL                 = local.database_url
    DB_USER                = "gateway_app"
    DB_APP_ROLE            = "gateway_app"
    DB_MIGRATE_ON_START    = "false"
  })

  app_secrets = {
    DB_PASSWORD             = "${var.db_app_secret_arn}:password::"
    SPRING_APPLICATION_JSON = var.app_config_secret_arn
  }

  adot = var.amp == null ? null : {
    image                = var.adot_image
    config               = file("${path.module}/adot-collector.yaml")
    amp_workspace_arn    = var.amp.workspace_arn
    amp_region           = var.amp.region
    amp_remote_write_url = var.amp.remote_write_url
  }
}

module "api" {
  source             = "../ecs-service"
  name               = "${var.name}-api"
  cluster_arn        = aws_ecs_cluster.this.arn
  image              = var.image
  environment        = merge(local.app_environment, { PG_WORKERS_ENABLED = "false" })
  secrets            = local.app_secrets
  secret_arns        = [var.db_app_secret_arn, var.app_config_secret_arn]
  kms_key_arns       = [var.data_kms_key_arn]
  desired_count      = var.api_capacity.desired
  min_count          = var.api_capacity.min
  max_count          = var.api_capacity.max
  subnet_ids         = module.network.private_subnet_ids
  security_group_ids = [aws_security_group.tasks.id]
  container_port     = 8080
  target_group_arn   = aws_lb_target_group.api.arn
  log_kms_key_arn    = var.telemetry_kms_key_arn
  adot               = local.adot
  tags               = var.tags

  depends_on = [aws_lb_listener.https]
}

module "worker" {
  source             = "../ecs-service"
  name               = "${var.name}-worker"
  cluster_arn        = aws_ecs_cluster.this.arn
  image              = var.image
  environment        = merge(local.app_environment, { PG_WORKERS_ENABLED = "true" })
  secrets            = local.app_secrets
  secret_arns        = [var.db_app_secret_arn, var.app_config_secret_arn]
  kms_key_arns       = [var.data_kms_key_arn]
  desired_count      = var.worker_capacity.desired
  min_count          = var.worker_capacity.min
  max_count          = var.worker_capacity.max
  subnet_ids         = module.network.private_subnet_ids
  security_group_ids = [aws_security_group.tasks.id]
  log_kms_key_arn    = var.telemetry_kms_key_arn
  adot               = local.adot
  tags               = var.tags
}

# One-off task run before each deploy (ADR-026): the migrator's secret only, no application secrets.
module "migrate" {
  source          = "../ecs-service"
  name            = "${var.name}-migrate"
  create_service  = false
  cluster_arn     = aws_ecs_cluster.this.arn
  image           = var.image
  cpu             = 512
  memory          = 1024
  log_kms_key_arn = var.telemetry_kms_key_arn
  tags            = var.tags

  environment = {
    SPRING_PROFILES_ACTIVE = "prod"
    PG_MIGRATE_ONLY        = "true"
    DB_URL                 = local.database_url
    DB_MIGRATION_USER      = "gateway_migrator"
    DB_APP_ROLE            = "gateway_app"
  }
  secrets      = { DB_MIGRATION_PASSWORD = "${var.db_migrator_secret_arn}:password::" }
  secret_arns  = [var.db_migrator_secret_arn]
  kms_key_arns = [var.data_kms_key_arn]
}
