output "web_acl_arn" {
  description = "Web ACL ARN."
  value       = aws_wafv2_web_acl.this.arn
}

output "log_group_name" {
  description = "WAF request log group (Authorization and Cookie redacted)."
  value       = aws_cloudwatch_log_group.waf.name
}
