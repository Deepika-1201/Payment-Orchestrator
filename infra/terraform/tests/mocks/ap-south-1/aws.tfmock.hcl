# Mock AWS for terraform test in ap-south-1 (ADR-028). The provider validates ARN arguments even when mocked, so every
# resource referenced by ARN gets a well-formed one.
mock_data "aws_availability_zones" {
  defaults = { names = ["ap-south-1a", "ap-south-1b", "ap-south-1c"] }
}

mock_data "aws_region" {
  defaults = { region = "ap-south-1" }
}

mock_data "aws_caller_identity" {
  defaults = { account_id = "123456789012" }
}

mock_data "aws_rds_engine_version" {
  defaults = { version_actual = "17.6" }
}

mock_data "aws_iam_policy_document" {
  defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
}

mock_resource "aws_secretsmanager_secret" {
  defaults = { arn = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:pg-prod/example-AbCdEf" }
}

mock_resource "aws_ecs_cluster" {
  defaults = { arn = "arn:aws:ecs:ap-south-1:123456789012:cluster/pg-prod-mum" }
}

mock_resource "aws_prometheus_workspace" {
  defaults = {
    arn                 = "arn:aws:aps:ap-south-1:123456789012:workspace/ws-example"
    prometheus_endpoint = "https://aps-workspaces.ap-south-1.amazonaws.com/workspaces/ws-example/"
  }
}

mock_resource "aws_kms_key" {
  defaults = { arn = "arn:aws:kms:ap-south-1:123456789012:key/11111111-1111-1111-1111-111111111111" }
}

mock_resource "aws_iam_role" {
  defaults = { arn = "arn:aws:iam::123456789012:role/pg-prod-mum-example" }
}

mock_resource "aws_cloudwatch_log_group" {
  defaults = { arn = "arn:aws:logs:ap-south-1:123456789012:log-group:pg-prod-mum-example" }
}

mock_resource "aws_lb" {
  defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:loadbalancer/app/pg-prod-mum/0123456789abcdef" }
}

mock_resource "aws_lb_target_group" {
  defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:targetgroup/api/0123456789abcdef" }
}

mock_resource "aws_wafv2_ip_set" {
  defaults = { arn = "arn:aws:wafv2:ap-south-1:123456789012:regional/ipset/pg-prod-mum/11111111-1111-1111-1111-111111111111" }
}

mock_resource "aws_wafv2_web_acl" {
  defaults = { arn = "arn:aws:wafv2:ap-south-1:123456789012:regional/webacl/pg-prod-mum/11111111-1111-1111-1111-111111111111" }
}

mock_resource "aws_sns_topic" {
  defaults = { arn = "arn:aws:sns:ap-south-1:123456789012:pg-prod-alerts" }
}

mock_resource "aws_ecs_task_definition" {
  defaults = { arn = "arn:aws:ecs:ap-south-1:123456789012:task-definition/pg-prod-mum:1" }
}

mock_resource "aws_rds_cluster" {
  defaults = {
    arn      = "arn:aws:rds:ap-south-1:123456789012:cluster:pg-prod-mum"
    endpoint = "pg-prod-mum.cluster-example.ap-south-1.rds.amazonaws.com"
  }
}

mock_resource "aws_rds_global_cluster" {
  defaults = { arn = "arn:aws:rds::123456789012:global-cluster:pg-prod", id = "pg-prod", engine_version = "17.6" }
}

mock_resource "aws_ecr_repository" {
  defaults = { repository_url = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/payment-gateway" }
}
