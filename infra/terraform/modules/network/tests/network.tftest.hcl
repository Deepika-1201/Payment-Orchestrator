# terraform test in modules/network (ADR-028): mocked apply, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name             = "pg-prod-mum"
  cidr             = "10.20.0.0/16"
  logs_kms_key_arn = "arn:aws:kms:ap-south-1:123456789012:key/33333333-3333-3333-3333-333333333333"
}

run "three_zones_with_static_egress_and_isolated_databases" {
  command = apply

  assert {
    condition     = length(aws_nat_gateway.this) == 3 && length(aws_eip.nat) == 3 && length(distinct(aws_route.private_nat[*].nat_gateway_id)) == 3
    error_message = "Each AZ routes through its own NAT gateway and Elastic IP."
  }

  assert {
    condition     = [for subnet in aws_subnet.public : subnet.cidr_block] == ["10.20.0.0/24", "10.20.1.0/24", "10.20.2.0/24"] && [for subnet in aws_subnet.private : subnet.cidr_block] == ["10.20.16.0/20", "10.20.32.0/20", "10.20.48.0/20"] && [for subnet in aws_subnet.database : subnet.cidr_block] == ["10.20.200.0/24", "10.20.201.0/24", "10.20.202.0/24"]
    error_message = "The subnet plan must not overlap."
  }

  assert {
    condition     = distinct(aws_subnet.private[*].availability_zone) == tolist(["ap-south-1a", "ap-south-1b", "ap-south-1c"])
    error_message = "Private subnets span three AZs."
  }

  assert {
    condition     = alltrue([for subnet in aws_subnet.public : subnet.map_public_ip_on_launch != true])
    error_message = "Nothing gets a public IP automatically."
  }

  assert {
    condition     = length(aws_route_table_association.database) == 3 && alltrue([for association in aws_route_table_association.database : association.route_table_id == aws_route_table.database.id])
    error_message = "Database subnets use the route table without a route out of the VPC."
  }

  assert {
    condition     = aws_flow_log.this.traffic_type == "ALL" && aws_cloudwatch_log_group.flow_logs.kms_key_id == var.logs_kms_key_arn
    error_message = "Flow logs record all traffic into an encrypted log group."
  }

  assert {
    condition     = contains(keys(aws_vpc_endpoint.interface), "secretsmanager") && alltrue([for endpoint in aws_vpc_endpoint.interface : endpoint.private_dns_enabled])
    error_message = "AWS API calls stay inside the VPC."
  }
}

run "a_cidr_other_than_a_16_is_rejected" {
  command = plan

  variables {
    cidr = "10.20.0.0/20"
  }

  expect_failures = [var.cidr]
}
