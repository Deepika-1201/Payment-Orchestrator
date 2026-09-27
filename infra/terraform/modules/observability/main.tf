data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

resource "aws_cloudwatch_log_group" "amp" {
  name              = "/aws/prometheus/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
  tags              = var.tags
}

resource "aws_prometheus_workspace" "this" {
  alias       = var.name
  kms_key_arn = var.kms_key_arn
  tags        = var.tags

  logging_configuration {
    log_group_arn = "${aws_cloudwatch_log_group.amp.arn}:*"
  }
}

resource "aws_prometheus_rule_group_namespace" "gateway" {
  name         = "payment-gateway"
  workspace_id = aws_prometheus_workspace.this.id
  data         = file(var.rules_file)
}

# critical pages the on-call engineer; warning opens a ticket (docs/runbooks.md).
resource "aws_sns_topic" "alerts" {
  for_each          = toset(["critical", "warning"])
  name              = "${var.name}-alerts-${each.key}"
  kms_master_key_id = var.kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "alerts" {
  for_each = aws_sns_topic.alerts

  statement {
    sid       = "PrometheusAlertmanagerPublishes"
    actions   = ["sns:Publish", "sns:GetTopicAttributes"]
    resources = [each.value.arn]
    principals {
      type        = "Service"
      identifiers = ["aps.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_prometheus_workspace.this.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }

  # CloudWatch alarms in this account (load balancer health, replication lag) page through the same topics.
  statement {
    sid       = "CloudWatchAlarmsPublish"
    actions   = ["sns:Publish"]
    resources = [each.value.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alerts" {
  for_each = aws_sns_topic.alerts
  arn      = each.value.arn
  policy   = data.aws_iam_policy_document.alerts[each.key].json
}

resource "aws_prometheus_alert_manager_definition" "this" {
  workspace_id = aws_prometheus_workspace.this.id
  definition = yamlencode({
    alertmanager_config = yamlencode({
      route = {
        receiver        = "warning"
        group_by        = ["alertname", "provider"]
        group_wait      = "30s"
        group_interval  = "5m"
        repeat_interval = "4h"
        routes = [{
          receiver = "critical"
          matchers = ["severity=\"critical\""]
        }]
      }
      receivers = [for severity, topic in aws_sns_topic.alerts : {
        name = severity
        sns_configs = [{
          topic_arn = topic.arn
          sigv4     = { region = data.aws_region.current.region }
        }]
      }]
    })
  })
}
