output "workspace_arn" {
  description = "Amazon Managed Prometheus workspace ARN."
  value       = aws_prometheus_workspace.this.arn
}

output "remote_write_url" {
  description = "Remote-write endpoint for the ADOT collectors."
  value       = "${aws_prometheus_workspace.this.prometheus_endpoint}api/v1/remote_write"
}

output "alert_topic_arns" {
  description = "SNS topics per severity; subscribe the paging tool and the ticket queue."
  value       = { for severity, topic in aws_sns_topic.alerts : severity => topic.arn }
}
