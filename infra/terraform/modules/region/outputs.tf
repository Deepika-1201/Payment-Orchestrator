output "alb_dns_name" {
  description = "Load balancer DNS name."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "Load balancer hosted zone (for Route 53 aliases)."
  value       = aws_lb.this.zone_id
}

output "nat_public_ips" {
  description = "Static egress addresses for PSP allowlists."
  value       = module.network.nat_public_ips
}

output "cluster_name" {
  description = "ECS cluster."
  value       = aws_ecs_cluster.this.name
}

output "private_subnet_ids" {
  description = "Subnets for run-task (the migration task)."
  value       = module.network.private_subnet_ids
}

output "task_security_group_id" {
  description = "Security group for run-task (the migration task)."
  value       = aws_security_group.tasks.id
}

output "migrate_task_definition_arn" {
  description = "Run this before each deploy, in the region whose cluster is the writer."
  value       = module.migrate.task_definition_arn
}

output "db_cluster_id" {
  description = "Aurora cluster identifier (for alarms and failover)."
  value       = module.aurora.cluster_id
}

output "db_cluster_arn" {
  description = "Aurora cluster ARN."
  value       = module.aurora.cluster_arn
}

output "db_endpoint" {
  description = "Aurora writer endpoint of this region."
  value       = module.aurora.endpoint
}

output "db_master_secret_arn" {
  description = "RDS-managed master secret (primary only), for the one-time role bootstrap."
  value       = module.aurora.master_user_secret_arn
}

output "service_names" {
  description = "ECS services."
  value       = { api = module.api.service_name, worker = module.worker.service_name }
}

output "no_healthy_api_task_alarm" {
  description = "Critical alarm when no API task passes its readiness check."
  value       = aws_cloudwatch_metric_alarm.no_healthy_api_task.alarm_name
}
