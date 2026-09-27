# terraform test in modules/ecs-service (ADR-028): mocked apply, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name               = "pg-prod-mum-api"
  cluster_arn        = "arn:aws:ecs:ap-south-1:123456789012:cluster/pg-prod-mum"
  image              = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/payment-gateway:901174a"
  environment        = { SPRING_PROFILES_ACTIVE = "prod" }
  secrets            = { DB_PASSWORD = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:pg-prod/db/gateway-app-AbCdEf:password::" }
  secret_arns        = ["arn:aws:secretsmanager:ap-south-1:123456789012:secret:pg-prod/db/gateway-app-AbCdEf"]
  kms_key_arns       = ["arn:aws:kms:ap-south-1:123456789012:key/11111111-1111-1111-1111-111111111111"]
  subnet_ids         = ["subnet-0000000000000000a"]
  security_group_ids = ["sg-0123456789abcdef0"]
  container_port     = 8080
  target_group_arn   = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:targetgroup/api/0123456789abcdef"
  log_kms_key_arn    = "arn:aws:kms:ap-south-1:123456789012:key/33333333-3333-3333-3333-333333333333"
}

run "service_tasks_are_locked_down" {
  command = apply

  assert {
    condition     = alltrue([for container in jsondecode(aws_ecs_task_definition.this.container_definitions) : container.readonlyRootFilesystem])
    error_message = "Containers have read-only root filesystems."
  }

  assert {
    condition     = !aws_ecs_service.this[0].network_configuration[0].assign_public_ip && !aws_ecs_service.this[0].enable_execute_command
    error_message = "Tasks get no public IP and no ECS Exec."
  }

  assert {
    condition     = aws_ecs_service.this[0].deployment_circuit_breaker[0].enable && aws_ecs_service.this[0].deployment_circuit_breaker[0].rollback
    error_message = "Failed deployments roll back automatically."
  }

  assert {
    condition     = [for mapping in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].portMappings : mapping.containerPort] == [8080, 8081]
    error_message = "The application and management ports are exposed to the load balancer."
  }

  assert {
    condition     = length(aws_iam_role_policy.execution_secrets) == 1 && length(aws_iam_role_policy.adot) == 0
    error_message = "The execution role reads this service's secrets; the task role gets nothing without a sidecar."
  }

  assert {
    condition     = startswith(aws_iam_role.task.name_prefix, "pg-prod-mum-api") && startswith(aws_iam_role.execution.name_prefix, "pg-prod-mum-api")
    error_message = "Each service has its own roles."
  }

  assert {
    condition     = aws_cloudwatch_log_group.this.kms_key_id == var.log_kms_key_arn && aws_cloudwatch_log_group.this.retention_in_days >= 180
    error_message = "Logs are encrypted and kept at least 180 days (CERT-In)."
  }

  assert {
    condition     = output.secret_names == tolist(["DB_PASSWORD"]) && !contains(keys(output.environment), "DB_PASSWORD")
    error_message = "Secrets are injected from Secrets Manager, never as plain environment variables."
  }
}

run "one_off_task_has_no_service" {
  command = apply

  variables {
    create_service   = false
    container_port   = null
    target_group_arn = null
  }

  assert {
    condition     = length(aws_ecs_service.this) == 0 && length(aws_appautoscaling_target.this) == 0 && output.service_name == null
    error_message = "A one-off task only has a task definition."
  }

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].portMappings) == 0
    error_message = "A one-off task exposes no ports."
  }
}

run "adot_sidecar_is_optional_and_non_essential" {
  command = apply

  variables {
    adot = {
      image                = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"
      config               = "receivers: {}"
      amp_workspace_arn    = "arn:aws:aps:ap-south-1:123456789012:workspace/ws-example"
      amp_region           = "ap-south-1"
      amp_remote_write_url = "https://aps-workspaces.ap-south-1.amazonaws.com/workspaces/ws-example/api/v1/remote_write"
    }
  }

  assert {
    condition     = [for container in jsondecode(aws_ecs_task_definition.this.container_definitions) : "${container.name}:${container.essential}"] == ["app:true", "adot:false"]
    error_message = "The collector runs beside the app and cannot take it down."
  }

  assert {
    condition     = output.environment.OTLP_TRACING_ENABLED == "true" && output.environment.OTLP_TRACING_ENDPOINT == "http://localhost:4318/v1/traces"
    error_message = "The application exports traces to the sidecar."
  }

  assert {
    condition     = length(aws_iam_role_policy.adot) == 1
    error_message = "The task role may write metrics and traces only when the sidecar runs."
  }
}
