data "aws_region" "current" {}

locals {
  region = data.aws_region.current.region
  # Short enough for IAM name_prefix limits.
  role_prefix = substr(replace(var.name, "_", "-"), 0, 24)

  log_options = {
    awslogs-group  = aws_cloudwatch_log_group.this.name
    awslogs-region = local.region
  }

  adot_environment = var.adot == null ? {} : {
    OTLP_TRACING_ENABLED  = "true"
    OTLP_TRACING_ENDPOINT = "http://localhost:4318/v1/traces"
  }

  app = {
    name                   = "app"
    image                  = var.image
    essential              = true
    readonlyRootFilesystem = true
    stopTimeout            = 60
    portMappings = var.container_port == null ? [] : [
      { containerPort = var.container_port, protocol = "tcp" },
      { containerPort = 8081, protocol = "tcp" },
    ]
    environment     = [for key, value in merge(var.environment, local.adot_environment) : { name = key, value = value }]
    secrets         = [for key, value in var.secrets : { name = key, valueFrom = value }]
    mountPoints     = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    linuxParameters = { initProcessEnabled = true }
    logConfiguration = {
      logDriver = "awslogs"
      options   = merge(local.log_options, { awslogs-stream-prefix = "app" })
    }
  }

  adot = var.adot == null ? [] : [{
    name                   = "adot"
    image                  = var.adot.image
    essential              = false
    readonlyRootFilesystem = true
    environment = [
      { name = "AOT_CONFIG_CONTENT", value = var.adot.config },
      { name = "AMP_REGION", value = var.adot.amp_region },
      { name = "AWS_PROMETHEUS_ENDPOINT", value = var.adot.amp_remote_write_url },
      { name = "AWS_PROMETHEUS_SCRAPING_ENDPOINT", value = "localhost:8081" },
    ]
    mountPoints = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    logConfiguration = {
      logDriver = "awslogs"
      options   = merge(local.log_options, { awslogs-stream-prefix = "adot" })
    }
  }]
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Execution role: pulls the image, writes logs, and reads only this service's secrets.
resource "aws_iam_role" "execution" {
  name_prefix        = "${local.role_prefix}-exec-"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  count = length(var.secret_arns) > 0 ? 1 : 0

  statement {
    sid       = "ReadThisServicesSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.secret_arns
  }

  statement {
    sid       = "DecryptThem"
    actions   = ["kms:Decrypt"]
    resources = var.kms_key_arns
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  count  = length(var.secret_arns) > 0 ? 1 : 0
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets[0].json
}

# Task role: what the running code may call. Separate per service (ADR-010); empty unless the sidecar needs it.
resource "aws_iam_role" "task" {
  name_prefix        = "${local.role_prefix}-task-"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "adot" {
  count = var.adot == null ? 0 : 1

  statement {
    sid       = "WriteMetrics"
    actions   = ["aps:RemoteWrite"]
    resources = [var.adot.amp_workspace_arn]
  }

  # X-Ray's write APIs do not support resource-level permissions.
  #trivy:ignore:AVD-AWS-0057
  statement {
    sid       = "WriteTraces"
    actions   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "adot" {
  count  = var.adot == null ? 0 : 1
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.adot[0].json
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions    = jsonencode(concat([local.app], local.adot))
  tags                     = var.tags

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  volume {
    name = "tmp"
  }
}

resource "aws_ecs_service" "this" {
  count                              = var.create_service ? 1 : 0
  name                               = var.name
  cluster                            = var.cluster_arn
  task_definition                    = aws_ecs_task_definition.this.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = var.target_group_arn == null ? null : 90
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  wait_for_steady_state              = false
  tags                               = var.tags

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = var.target_group_arn == null ? [] : [var.target_group_arn]
    content {
      target_group_arn = load_balancer.value
      container_name   = "app"
      container_port   = var.container_port
    }
  }

  lifecycle {
    ignore_changes = [desired_count]
  }
}

resource "aws_appautoscaling_target" "this" {
  count              = var.create_service ? 1 : 0
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${element(split("/", var.cluster_arn), 1)}/${aws_ecs_service.this[0].name}"
  min_capacity       = var.min_count
  max_capacity       = var.max_count
}

resource "aws_appautoscaling_policy" "cpu" {
  count              = var.create_service ? 1 : 0
  name               = "${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
