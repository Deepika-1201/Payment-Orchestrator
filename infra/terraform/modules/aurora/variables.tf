variable "name" {
  description = "Cluster identifier, for example pg-prod-mum."
  type        = string
}

variable "role" {
  description = "primary (writer, owns the database) or secondary (Aurora Global Database replica in another region)."
  type        = string

  validation {
    condition     = contains(["primary", "secondary"], var.role)
    error_message = "role must be primary or secondary."
  }
}

variable "global_cluster_identifier" {
  description = "Aurora Global Database this cluster belongs to."
  type        = string
}

variable "engine_version" {
  description = "Aurora PostgreSQL version, the same in both regions (for example 17.6)."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the cluster."
  type        = string
}

variable "subnet_ids" {
  description = "Isolated database subnets, one per AZ."
  type        = list(string)
}

variable "client_security_group_ids" {
  description = "Security groups allowed to connect on 5432 (the gateway tasks)."
  type        = list(string)
}

variable "kms_key_arn" {
  description = "Key for storage, Performance Insights and the managed master secret."
  type        = string
}

variable "instance_class" {
  description = "Instance class; db.serverless for Serverless v2 (non-production)."
  type        = string
  default     = "db.r7g.large"
}

variable "instance_count" {
  description = "Writer plus readers, spread across AZs."
  type        = number
  default     = 2
}

variable "serverless_capacity" {
  description = "Serverless v2 ACU range when instance_class is db.serverless."
  type = object({
    min = number
    max = number
  })
  default = null
}

variable "backup_retention_days" {
  description = "Point-in-time recovery window."
  type        = number
  default     = 35
}

variable "deletion_protection" {
  description = "Refuse deletion of the cluster."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
