variable "name" {
  description = "Regional prefix, for example pg-prod-mum."
  type        = string
}

variable "cidr" {
  description = "VPC /16 for this region."
  type        = string
}

variable "image" {
  description = "Gateway image in this region's registry (immutable tag)."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for the API domain in this region."
  type        = string
}

variable "db_role" {
  description = "primary or secondary Aurora cluster in the global database."
  type        = string
}

variable "global_cluster_identifier" {
  description = "Aurora Global Database identifier."
  type        = string
}

variable "engine_version" {
  description = "Aurora PostgreSQL version."
  type        = string
}

variable "db_instance_class" {
  description = "Aurora instance class."
  type        = string
  default     = "db.r7g.large"
}

variable "db_instance_count" {
  description = "Aurora instances (writer plus readers)."
  type        = number
  default     = 2
}

variable "db_deletion_protection" {
  description = "Refuse deletion of the Aurora cluster."
  type        = bool
  default     = true
}

variable "api_capacity" {
  description = "API service task counts."
  type = object({
    desired = number
    min     = number
    max     = number
  })
}

variable "worker_capacity" {
  description = "Worker service task counts (0 in a standby region: its database is read-only until failover)."
  type = object({
    desired = number
    min     = number
    max     = number
  })
}

variable "psp_webhook_ipv4_cidrs" {
  description = "PSP webhook source ranges (WAF)."
  type        = list(string)
}

variable "psp_webhook_ipv6_cidrs" {
  description = "PSP webhook IPv6 source ranges (WAF)."
  type        = list(string)
  default     = []
}

variable "admin_ipv4_cidrs" {
  description = "Operator networks allowed to reach /admin/ (WAF)."
  type        = list(string)
}

variable "app_environment" {
  description = "Non-secret application settings shared by the API and worker services."
  type        = map(string)
  default     = {}
}

variable "db_app_secret_arn" {
  description = "Secret with the gateway_app role's credentials, in this region."
  type        = string
}

variable "db_migrator_secret_arn" {
  description = "Secret with the gateway_migrator role's credentials, in this region."
  type        = string
}

variable "app_config_secret_arn" {
  description = "Secret holding SPRING_APPLICATION_JSON with the data keys (ADR-025), in this region."
  type        = string
}

variable "data_kms_key_arn" {
  description = "Data key of this region."
  type        = string
}

variable "telemetry_kms_key_arn" {
  description = "Telemetry key of this region."
  type        = string
}

variable "amp" {
  description = "Amazon Managed Prometheus workspace the ADOT sidecars write to."
  type = object({
    workspace_arn    = string
    region           = string
    remote_write_url = string
  })
  default = null
}

variable "adot_image" {
  description = "ADOT collector image."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"
}

variable "critical_alarm_topic_arn" {
  description = "SNS topic in this region for critical CloudWatch alarms."
  type        = string
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
