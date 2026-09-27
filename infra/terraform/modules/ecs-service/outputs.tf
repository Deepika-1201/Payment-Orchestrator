output "task_definition_arn" {
  description = "Task definition (for run-task, in the case of the migration task)."
  value       = aws_ecs_task_definition.this.arn
}

output "service_name" {
  description = "ECS service name, if a service was created."
  value       = try(aws_ecs_service.this[0].name, null)
}

output "task_role_arn" {
  description = "Role the running code assumes."
  value       = aws_iam_role.task.arn
}

output "execution_role_arn" {
  description = "Role ECS uses to start the task."
  value       = aws_iam_role.execution.arn
}

output "log_group_name" {
  description = "CloudWatch log group."
  value       = aws_cloudwatch_log_group.this.name
}

output "environment" {
  description = "Plain environment variables the application container runs with (never secrets)."
  value       = merge(var.environment, local.adot_environment)
}

output "secret_names" {
  description = "Environment variables injected from Secrets Manager (names only)."
  value       = sort(keys(var.secrets))
}

output "secret_sources" {
  description = "Secrets Manager ARNs (with JSON keys) the container reads."
  value       = sort(values(var.secrets))
}
