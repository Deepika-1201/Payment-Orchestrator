# terraform test in modules/observability (ADR-027, ADR-028): mocked apply, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name        = "pg-prod"
  rules_file  = "../../../../deploy/observability/prometheus/rules/payment-gateway.yml"
  kms_key_arn = "arn:aws:kms:ap-south-1:123456789012:key/33333333-3333-3333-3333-333333333333"
}

run "alerts_reach_the_right_people_and_nobody_else" {
  command = apply

  assert {
    condition     = aws_prometheus_rule_group_namespace.gateway.data == file(var.rules_file) && strcontains(aws_prometheus_rule_group_namespace.gateway.data, "alert: ApiErrorBudgetBurnFast")
    error_message = "AWS loads the same rule file that the local stack and the promtool tests use."
  }

  assert {
    condition     = aws_prometheus_workspace.this.kms_key_arn == var.kms_key_arn && alltrue([for topic in aws_sns_topic.alerts : topic.kms_master_key_id == var.kms_key_arn])
    error_message = "Metrics and alert topics are encrypted with the telemetry key."
  }

  assert {
    condition     = yamldecode(yamldecode(aws_prometheus_alert_manager_definition.this.definition).alertmanager_config).route.routes[0].receiver == "critical" && yamldecode(yamldecode(aws_prometheus_alert_manager_definition.this.definition).alertmanager_config).route.routes[0].matchers == ["severity=\"critical\""] && yamldecode(yamldecode(aws_prometheus_alert_manager_definition.this.definition).alertmanager_config).route.receiver == "warning"
    error_message = "Critical alerts page; everything else opens a ticket."
  }

  # One assert per topic: iterating over the whole policy map makes Terraform 1.16 crash while reporting a failure.
  assert {
    condition = (
      toset(flatten([for statement in data.aws_iam_policy_document.alerts["critical"].statement : [for principal in statement.principals : principal.identifiers]])) == toset(["aps.amazonaws.com", "cloudwatch.amazonaws.com"])
      && contains(flatten([for statement in data.aws_iam_policy_document.alerts["critical"].statement : [for condition in statement.condition : condition.values if condition.variable == "aws:SourceArn"]]), aws_prometheus_workspace.this.arn)
      && alltrue([for statement in data.aws_iam_policy_document.alerts["critical"].statement : contains([for condition in statement.condition : condition.variable], "aws:SourceAccount")])
    )
    error_message = "Only this workspace and this account's CloudWatch alarms may publish to the critical topic."
  }

  assert {
    condition = (
      toset(flatten([for statement in data.aws_iam_policy_document.alerts["warning"].statement : [for principal in statement.principals : principal.identifiers]])) == toset(["aps.amazonaws.com", "cloudwatch.amazonaws.com"])
      && contains(flatten([for statement in data.aws_iam_policy_document.alerts["warning"].statement : [for condition in statement.condition : condition.values if condition.variable == "aws:SourceArn"]]), aws_prometheus_workspace.this.arn)
      && alltrue([for statement in data.aws_iam_policy_document.alerts["warning"].statement : contains([for condition in statement.condition : condition.variable], "aws:SourceAccount")])
    )
    error_message = "Only this workspace and this account's CloudWatch alarms may publish to the warning topic."
  }
}
