variable "name" {
  description = "Name prefix, for example pg-prod-mum."
  type        = string
}

variable "cidr" {
  description = "VPC CIDR; a /16 gives /24 public, /20 private and /24 database subnets per AZ."
  type        = string

  validation {
    condition     = can(cidrhost(var.cidr, 0)) && endswith(var.cidr, "/16")
    error_message = "The VPC CIDR must be a valid /16."
  }
}

variable "az_count" {
  description = "Availability zones to span."
  type        = number
  default     = 3

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "Use 2 or 3 availability zones."
  }
}

variable "interface_endpoints" {
  description = "AWS services reached through interface endpoints instead of the NAT gateways."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "kms", "sts", "aps-workspaces", "xray"]
}

variable "logs_kms_key_arn" {
  description = "KMS key for the flow log group."
  type        = string
}

variable "log_retention_days" {
  description = "Flow log retention (CERT-In requires at least 180 days)."
  type        = number
  default     = 365
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
