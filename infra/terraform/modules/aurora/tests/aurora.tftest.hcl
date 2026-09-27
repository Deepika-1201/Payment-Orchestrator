# terraform test in modules/aurora (ADR-028): mocked apply, no AWS credentials.
mock_provider "aws" {
  source = "../../tests/mocks/ap-south-1"
}

variables {
  name                      = "pg-prod-mum"
  role                      = "primary"
  global_cluster_identifier = "pg-prod"
  engine_version            = "17.6"
  vpc_id                    = "vpc-0123456789abcdef0"
  subnet_ids                = ["subnet-0000000000000000a", "subnet-0000000000000000b", "subnet-0000000000000000c"]
  client_security_group_ids = ["sg-0123456789abcdef0"]
  kms_key_arn               = "arn:aws:kms:ap-south-1:123456789012:key/11111111-1111-1111-1111-111111111111"
}

run "primary_cluster_is_encrypted_tls_only_and_recoverable" {
  command = apply

  assert {
    condition     = aws_rds_cluster.this.storage_encrypted && aws_rds_cluster.this.kms_key_id == var.kms_key_arn
    error_message = "Storage is encrypted with the data key."
  }

  assert {
    condition     = anytrue([for parameter in aws_rds_cluster_parameter_group.this.parameter : parameter.name == "rds.force_ssl" && parameter.value == "1"]) && aws_rds_cluster_parameter_group.this.family == "aurora-postgresql17"
    error_message = "The server refuses connections without TLS."
  }

  assert {
    condition     = aws_rds_cluster.this.backup_retention_period == 35 && aws_rds_cluster.this.deletion_protection && !aws_rds_cluster.this.skip_final_snapshot
    error_message = "35 days of point-in-time recovery, deletion protection and a final snapshot."
  }

  assert {
    condition     = aws_rds_cluster.this.manage_master_user_password && aws_rds_cluster.this.master_user_secret_kms_key_id == var.kms_key_arn
    error_message = "RDS manages the master password in Secrets Manager, so it never appears in Terraform."
  }

  assert {
    condition     = alltrue([for instance in aws_rds_cluster_instance.this : !instance.publicly_accessible && instance.performance_insights_kms_key_id == var.kms_key_arn])
    error_message = "Instances are private and encrypt Performance Insights."
  }

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.clients) == 1 && aws_vpc_security_group_ingress_rule.clients[0].referenced_security_group_id == "sg-0123456789abcdef0" && aws_vpc_security_group_ingress_rule.clients[0].from_port == 5432
    error_message = "Only the gateway tasks reach PostgreSQL."
  }
}

run "secondary_cluster_has_no_credentials_of_its_own" {
  command = apply

  variables {
    role           = "secondary"
    instance_count = 1
  }

  # database_name, master_username and the secret key id are computed, so mocks fill them; manage_master_user_password is not.
  assert {
    condition     = aws_rds_cluster.this.manage_master_user_password == null
    error_message = "A global secondary takes its database and users from the primary."
  }

  assert {
    condition     = aws_rds_cluster.this.global_cluster_identifier == "pg-prod" && length(aws_rds_cluster_instance.this) == 1
    error_message = "The secondary joins the global database."
  }
}

run "an_unknown_role_is_rejected" {
  command = plan

  variables {
    role = "replica"
  }

  expect_failures = [var.role]
}
