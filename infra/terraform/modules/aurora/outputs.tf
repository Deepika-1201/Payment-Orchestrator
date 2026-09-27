output "cluster_id" {
  description = "Cluster identifier."
  value       = aws_rds_cluster.this.id
}

output "cluster_arn" {
  description = "Cluster ARN."
  value       = aws_rds_cluster.this.arn
}

output "endpoint" {
  description = "Writer endpoint of this regional cluster (read-only while it is a secondary)."
  value       = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  description = "Reader endpoint."
  value       = aws_rds_cluster.this.reader_endpoint
}

output "security_group_id" {
  description = "Security group of the cluster."
  value       = aws_security_group.this.id
}

output "master_user_secret_arn" {
  description = "Secrets Manager secret holding the RDS-managed master password (primary only)."
  value       = try(aws_rds_cluster.this.master_user_secret[0].secret_arn, null)
}
