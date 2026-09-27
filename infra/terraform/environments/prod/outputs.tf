output "psp_egress_ips" {
  description = "Give these to every PSP for their API allowlists (NAT Elastic IPs in both regions)."
  value = {
    (var.primary_region) = module.primary.nat_public_ips
    (var.dr_region)      = module.dr.nat_public_ips
  }
}

output "load_balancers" {
  description = "Load balancer DNS names per region."
  value = {
    (var.primary_region) = module.primary.alb_dns_name
    (var.dr_region)      = module.dr.alb_dns_name
  }
}

output "ecr_repository_url" {
  description = "Push images here; ECR replicates them to the standby region."
  value       = aws_ecr_repository.gateway.repository_url
}

output "migration_task" {
  description = "Inputs for aws ecs run-task before each deploy (primary region)."
  value = {
    cluster         = module.primary.cluster_name
    task_definition = module.primary.migrate_task_definition_arn
    subnets         = module.primary.private_subnet_ids
    security_group  = module.primary.task_security_group_id
  }
}

output "secrets_to_populate" {
  description = "Set before the first deploy: SPRING_APPLICATION_JSON with the data keys (ADR-025, ADR-028)."
  value       = aws_secretsmanager_secret.app_config.arn
}

output "standby_secret_arns" {
  description = "Secret replicas the standby region's tasks read."
  value       = local.dr_secret_arns
}

output "database_bootstrap" {
  description = "For the one-time role bootstrap (deploy/db/roles.sql): master secret and role secrets."
  value = {
    master_secret   = module.primary.db_master_secret_arn
    app_secret      = aws_secretsmanager_secret.db_app.arn
    migrator_secret = aws_secretsmanager_secret.db_migrator.arn
  }
}

output "alert_topics" {
  description = "Subscribe the paging tool to critical and the ticket queue to warning."
  value = merge(module.observability.alert_topic_arns, {
    critical_standby_region = aws_sns_topic.dr_critical.arn
  })
}
