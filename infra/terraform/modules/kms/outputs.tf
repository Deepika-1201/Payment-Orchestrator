output "data_key_arn" {
  description = "Key for Aurora, Secrets Manager and ECR."
  value       = aws_kms_key.data.arn
}

output "telemetry_key_arn" {
  description = "Key for CloudWatch Logs, Amazon Managed Prometheus and SNS."
  value       = aws_kms_key.telemetry.arn
}
