locals {
  metric = replace(var.name, "-", "_")

  managed_rule_groups = [
    { name = "AWSManagedRulesAmazonIpReputationList", priority = 20, count_only = [] },
    # The application caps bodies at 256 KB itself (ADR-022); the managed 8 KB body rule would block large PSP webhooks.
    { name = "AWSManagedRulesCommonRuleSet", priority = 21, count_only = ["SizeRestrictions_BODY"] },
    { name = "AWSManagedRulesKnownBadInputsRuleSet", priority = 22, count_only = [] },
    { name = "AWSManagedRulesSQLiRuleSet", priority = 23, count_only = [] },
  ]
}

resource "aws_wafv2_ip_set" "psp_v4" {
  name               = "${var.name}-psp-webhooks-v4"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.psp_webhook_ipv4_cidrs
  tags               = var.tags
}

resource "aws_wafv2_ip_set" "psp_v6" {
  name               = "${var.name}-psp-webhooks-v6"
  scope              = "REGIONAL"
  ip_address_version = "IPV6"
  addresses          = var.psp_webhook_ipv6_cidrs
  tags               = var.tags
}

resource "aws_wafv2_ip_set" "admin" {
  name               = "${var.name}-operators"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.admin_ipv4_cidrs
  tags               = var.tags
}

resource "aws_wafv2_web_acl" "this" {
  name        = var.name
  description = "Payment gateway edge rules (ADR-028)"
  scope       = "REGIONAL"
  tags        = var.tags

  default_action {
    allow {}
  }

  # The management port is not routed through the load balancer; block the path anyway.
  rule {
    name     = "block-management-paths"
    priority = 0

    action {
      block {}
    }

    statement {
      byte_match_statement {
        search_string         = "/actuator"
        positional_constraint = "STARTS_WITH"
        field_to_match {
          uri_path {}
        }
        text_transformation {
          priority = 0
          type     = "URL_DECODE"
        }
        text_transformation {
          priority = 1
          type     = "NORMALIZE_PATH"
        }
        text_transformation {
          priority = 2
          type     = "LOWERCASE"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.metric}_management_paths"
      sampled_requests_enabled   = true
    }
  }

  # Only PSPs may call the webhook endpoints; the application checks the source per provider too (ADR-022).
  rule {
    name     = "psp-webhooks-from-psp-addresses-only"
    priority = 1

    action {
      block {}
    }

    statement {
      and_statement {
        statement {
          byte_match_statement {
            search_string         = "/v1/webhooks/providers/"
            positional_constraint = "STARTS_WITH"
            field_to_match {
              uri_path {}
            }
            text_transformation {
              priority = 0
              type     = "NORMALIZE_PATH"
            }
          }
        }
        statement {
          not_statement {
            statement {
              ip_set_reference_statement {
                arn = aws_wafv2_ip_set.psp_v4.arn
              }
            }
          }
        }
        statement {
          not_statement {
            statement {
              ip_set_reference_statement {
                arn = aws_wafv2_ip_set.psp_v6.arn
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.metric}_psp_webhook_sources"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "admin-api-from-operator-networks-only"
    priority = 2

    action {
      block {}
    }

    statement {
      and_statement {
        statement {
          byte_match_statement {
            search_string         = "/admin/"
            positional_constraint = "STARTS_WITH"
            field_to_match {
              uri_path {}
            }
            text_transformation {
              priority = 0
              type     = "NORMALIZE_PATH"
            }
            text_transformation {
              priority = 1
              type     = "LOWERCASE"
            }
          }
        }
        statement {
          not_statement {
            statement {
              ip_set_reference_statement {
                arn = aws_wafv2_ip_set.admin.arn
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.metric}_admin_networks"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "admin-api-from-allowed-countries-only"
    priority = 3

    action {
      block {}
    }

    statement {
      and_statement {
        statement {
          byte_match_statement {
            search_string         = "/admin/"
            positional_constraint = "STARTS_WITH"
            field_to_match {
              uri_path {}
            }
            text_transformation {
              priority = 0
              type     = "NORMALIZE_PATH"
            }
            text_transformation {
              priority = 1
              type     = "LOWERCASE"
            }
          }
        }
        statement {
          not_statement {
            statement {
              geo_match_statement {
                country_codes = var.admin_countries
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.metric}_admin_countries"
      sampled_requests_enabled   = true
    }
  }

  dynamic "rule" {
    for_each = length(var.blocked_countries) == 0 ? [] : [var.blocked_countries]
    content {
      name     = "blocked-countries"
      priority = 4

      action {
        block {}
      }

      statement {
        geo_match_statement {
          country_codes = rule.value
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${local.metric}_blocked_countries"
        sampled_requests_enabled   = true
      }
    }
  }

  # PSP webhooks arrive in bursts and are already limited to PSP addresses, so they are not counted.
  rule {
    name     = "rate-limit-per-ip"
    priority = 10

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.rate_limit
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300

        scope_down_statement {
          not_statement {
            statement {
              byte_match_statement {
                search_string         = "/v1/webhooks/providers/"
                positional_constraint = "STARTS_WITH"
                field_to_match {
                  uri_path {}
                }
                text_transformation {
                  priority = 0
                  type     = "NORMALIZE_PATH"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.metric}_rate_limit"
      sampled_requests_enabled   = true
    }
  }

  dynamic "rule" {
    for_each = local.managed_rule_groups
    content {
      name     = rule.value.name
      priority = rule.value.priority

      override_action {
        none {}
      }

      statement {
        managed_rule_group_statement {
          vendor_name = "AWS"
          name        = rule.value.name

          dynamic "rule_action_override" {
            for_each = rule.value.count_only
            content {
              name = rule_action_override.value
              action_to_use {
                count {}
              }
            }
          }
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${local.metric}_${rule.value.name}"
        sampled_requests_enabled   = true
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.metric
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}

# WAF requires the aws-waf-logs- prefix.
resource "aws_cloudwatch_log_group" "waf" {
  name              = "aws-waf-logs-${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
  tags              = var.tags
}

# Merchant API keys and admin tokens travel in Authorization; never write them to logs.
resource "aws_wafv2_web_acl_logging_configuration" "this" {
  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "cookie"
    }
  }
}
