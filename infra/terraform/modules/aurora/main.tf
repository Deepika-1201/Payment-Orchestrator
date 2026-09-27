locals {
  primary = var.role == "primary"
  family  = "aurora-postgresql${split(".", var.engine_version)[0]}"
}

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

# No egress rules: Aurora never initiates connections.
resource "aws_security_group" "this" {
  name_prefix = "${var.name}-db-"
  description = "PostgreSQL from the gateway tasks only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-db" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "clients" {
  count                        = length(var.client_security_group_ids)
  security_group_id            = aws_security_group.this.id
  description                  = "PostgreSQL from gateway tasks"
  referenced_security_group_id = var.client_security_group_ids[count.index]
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_rds_cluster_parameter_group" "this" {
  name_prefix = "${var.name}-"
  family      = local.family
  description = "TLS-only connections and DDL audit logging for the payment gateway"

  # The server refuses non-TLS connections; clients also verify the certificate (sslmode=verify-full, ADR-026).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_statement"
    value = "ddl"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_rds_cluster" "this" {
  cluster_identifier              = var.name
  engine                          = "aurora-postgresql"
  engine_version                  = var.engine_version
  global_cluster_identifier       = var.global_cluster_identifier
  database_name                   = local.primary ? "payments" : null
  master_username                 = local.primary ? "gateway_admin" : null
  manage_master_user_password     = local.primary ? true : null
  master_user_secret_kms_key_id   = local.primary ? var.kms_key_arn : null
  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [aws_security_group.this.id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.this.name
  storage_encrypted               = true
  kms_key_id                      = var.kms_key_arn
  backup_retention_period         = var.backup_retention_days
  preferred_backup_window         = "20:30-21:30"
  preferred_maintenance_window    = "sun:22:00-sun:23:00"
  copy_tags_to_snapshot           = true
  deletion_protection             = var.deletion_protection
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name}-final"
  enabled_cloudwatch_logs_exports = ["postgresql"]
  tags                            = var.tags

  dynamic "serverlessv2_scaling_configuration" {
    for_each = var.serverless_capacity == null ? [] : [var.serverless_capacity]
    content {
      min_capacity = serverlessv2_scaling_configuration.value.min
      max_capacity = serverlessv2_scaling_configuration.value.max
    }
  }

  lifecycle {
    # auto_minor_version_upgrade moves the version; a secondary's replication source changes on managed failover.
    ignore_changes = [engine_version, replication_source_identifier]
  }
}

data "aws_iam_policy_document" "monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  name_prefix        = "${var.name}-rdsmon-"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_rds_cluster_instance" "this" {
  count                                 = var.instance_count
  identifier                            = "${var.name}-${count.index + 1}"
  cluster_identifier                    = aws_rds_cluster.this.id
  engine                                = aws_rds_cluster.this.engine
  engine_version                        = aws_rds_cluster.this.engine_version
  instance_class                        = var.instance_class
  db_subnet_group_name                  = aws_db_subnet_group.this.name
  publicly_accessible                   = false
  auto_minor_version_upgrade            = true
  ca_cert_identifier                    = "rds-ca-rsa2048-g1"
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.kms_key_arn
  performance_insights_retention_period = 7
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.monitoring.arn
  tags                                  = var.tags

  lifecycle {
    ignore_changes = [engine_version]
  }
}
