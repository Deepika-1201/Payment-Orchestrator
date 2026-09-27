# terraform test in modules/waf (ADR-028): mocked apply, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name                   = "pg-prod-mum"
  alb_arn                = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:loadbalancer/app/pg-prod-mum/0123456789abcdef"
  psp_webhook_ipv4_cidrs = ["192.0.2.0/28", "198.51.100.0/28"]
  admin_ipv4_cidrs       = ["203.0.113.0/24"]
  logs_kms_key_arn       = "arn:aws:kms:ap-south-1:123456789012:key/33333333-3333-3333-3333-333333333333"
}

run "edge_rules_protect_webhooks_admin_and_management" {
  command = apply

  assert {
    condition = alltrue([for required in ["block-management-paths", "psp-webhooks-from-psp-addresses-only", "admin-api-from-operator-networks-only", "admin-api-from-allowed-countries-only", "rate-limit-per-ip", "AWSManagedRulesAmazonIpReputationList", "AWSManagedRulesCommonRuleSet", "AWSManagedRulesKnownBadInputsRuleSet", "AWSManagedRulesSQLiRuleSet"] :
    contains([for rule in aws_wafv2_web_acl.this.rule : rule.name], required)])
    error_message = "The web ACL is missing a required rule."
  }

  assert {
    condition     = alltrue([for rule in aws_wafv2_web_acl.this.rule : length(rule.action[0].block) == 1 if rule.priority < 20])
    error_message = "Every custom rule must block."
  }

  assert {
    condition     = aws_wafv2_ip_set.psp_v4.addresses == toset(["192.0.2.0/28", "198.51.100.0/28"]) && aws_wafv2_ip_set.admin.addresses == toset(["203.0.113.0/24"])
    error_message = "The IP sets must hold exactly the configured PSP and operator ranges."
  }

  assert {
    condition     = one([for rule in aws_wafv2_web_acl.this.rule : rule.statement[0].managed_rule_group_statement[0].rule_action_override[0].name if rule.name == "AWSManagedRulesCommonRuleSet"]) == "SizeRestrictions_BODY"
    error_message = "Only the managed 8 KB body rule is relaxed (the application caps bodies at 256 KB)."
  }

  assert {
    condition     = one([for rule in aws_wafv2_web_acl.this.rule : rule.statement[0].rate_based_statement[0].limit if rule.name == "rate-limit-per-ip"]) == 2000
    error_message = "Clients are rate limited per IP."
  }

  assert {
    condition     = toset([for field in aws_wafv2_web_acl_logging_configuration.this.redacted_fields : field.single_header[0].name]) == toset(["authorization", "cookie"])
    error_message = "WAF logs must redact Authorization (API keys, admin tokens) and Cookie."
  }

  assert {
    condition     = startswith(aws_cloudwatch_log_group.waf.name, "aws-waf-logs-") && aws_cloudwatch_log_group.waf.kms_key_id == var.logs_kms_key_arn
    error_message = "WAF logs go to a KMS-encrypted log group with the required prefix."
  }

  assert {
    condition     = aws_wafv2_web_acl_association.alb.resource_arn == var.alb_arn
    error_message = "The web ACL must protect the load balancer."
  }

  assert {
    condition     = !contains([for rule in aws_wafv2_web_acl.this.rule : rule.name], "blocked-countries")
    error_message = "No country is blocked unless configured."
  }
}

run "blocked_countries_add_a_blocking_rule" {
  command = apply

  variables {
    blocked_countries = ["KP"]
  }

  assert {
    condition     = one([for rule in aws_wafv2_web_acl.this.rule : rule.statement[0].geo_match_statement[0].country_codes if rule.name == "blocked-countries"]) == tolist(["KP"])
    error_message = "Blocked countries become a geo rule."
  }
}

run "an_empty_psp_allowlist_is_rejected" {
  command = plan

  variables {
    psp_webhook_ipv4_cidrs = []
  }

  expect_failures = [var.psp_webhook_ipv4_cidrs]
}
