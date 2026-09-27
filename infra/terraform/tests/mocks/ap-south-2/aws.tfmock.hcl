# Mock AWS for terraform test in ap-south-2, the standby region (ADR-028).
mock_data "aws_availability_zones" {
  defaults = { names = ["ap-south-2a", "ap-south-2b", "ap-south-2c"] }
}

mock_data "aws_region" {
  defaults = { region = "ap-south-2" }
}

mock_data "aws_caller_identity" {
  defaults = { account_id = "123456789012" }
}

mock_data "aws_iam_policy_document" {
  defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
}

mock_resource "aws_ecs_cluster" {
  defaults = { arn = "arn:aws:ecs:ap-south-2:123456789012:cluster/pg-prod-hyd" }
}

mock_resource "aws_kms_key" {
  defaults = { arn = "arn:aws:kms:ap-south-2:123456789012:key/22222222-2222-2222-2222-222222222222" }
}

mock_resource "aws_iam_role" {
  defaults = { arn = "arn:aws:iam::123456789012:role/pg-prod-hyd-example" }
}

mock_resource "aws_cloudwatch_log_group" {
  defaults = { arn = "arn:aws:logs:ap-south-2:123456789012:log-group:pg-prod-hyd-example" }
}

mock_resource "aws_lb" {
  defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-2:123456789012:loadbalancer/app/pg-prod-hyd/0123456789abcdef" }
}

mock_resource "aws_lb_target_group" {
  defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-2:123456789012:targetgroup/api/0123456789abcdef" }
}

mock_resource "aws_wafv2_ip_set" {
  defaults = { arn = "arn:aws:wafv2:ap-south-2:123456789012:regional/ipset/pg-prod-hyd/22222222-2222-2222-2222-222222222222" }
}

mock_resource "aws_wafv2_web_acl" {
  defaults = { arn = "arn:aws:wafv2:ap-south-2:123456789012:regional/webacl/pg-prod-hyd/22222222-2222-2222-2222-222222222222" }
}

mock_resource "aws_sns_topic" {
  defaults = { arn = "arn:aws:sns:ap-south-2:123456789012:pg-prod-hyd-alerts" }
}

mock_resource "aws_ecs_task_definition" {
  defaults = { arn = "arn:aws:ecs:ap-south-2:123456789012:task-definition/pg-prod-hyd:1" }
}

mock_resource "aws_rds_cluster" {
  defaults = {
    arn      = "arn:aws:rds:ap-south-2:123456789012:cluster:pg-prod-hyd"
    endpoint = "pg-prod-hyd.cluster-example.ap-south-2.rds.amazonaws.com"
  }
}
