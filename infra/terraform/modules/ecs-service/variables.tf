variable "name" {
  description = "Service and task family name, for example pg-prod-mum-api."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster."
  type        = string
}

variable "image" {
  description = "Container image (immutable tag)."
  type        = string
}

variable "cpu" {
  description = "Task CPU units."
  type        = number
  default     = 1024
}

variable "memory" {
  description = "Task memory (MiB)."
  type        = number
  default     = 2048
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64; must match the image."
  type        = string
  default     = "X86_64"
}

variable "environment" {
  description = "Plain environment variables (never secrets)."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variables injected from Secrets Manager: name => secret ARN, optionally with :json-key::."
  type        = map(string)
  default     = {}
}

variable "secret_arns" {
  description = "Secrets the execution role may read: exactly the ones in var.secrets, without JSON-key suffixes."
  type        = list(string)
  default     = []
}

variable "kms_key_arns" {
  description = "Keys the execution role may decrypt the secrets with."
  type        = list(string)
  default     = []
}

variable "create_service" {
  description = "False for one-off tasks (the migration task) that only need a task definition."
  type        = bool
  default     = true
}

variable "desired_count" {
  description = "Initial task count; autoscaling owns it afterwards."
  type        = number
  default     = 2
}

variable "min_count" {
  description = "Autoscaling minimum."
  type        = number
  default     = 2
}

variable "max_count" {
  description = "Autoscaling maximum."
  type        = number
  default     = 10
}

variable "subnet_ids" {
  description = "Private subnets for the tasks."
  type        = list(string)
  default     = []
}

variable "security_group_ids" {
  description = "Security groups for the tasks."
  type        = list(string)
  default     = []
}

variable "container_port" {
  description = "Application port behind the load balancer; the management port 8081 is exposed with it."
  type        = number
  default     = null
}

variable "target_group_arn" {
  description = "Load balancer target group, if the service takes traffic."
  type        = string
  default     = null
}

variable "log_kms_key_arn" {
  description = "Key for the log group."
  type        = string
}

variable "log_retention_days" {
  description = "Log retention (CERT-In requires at least 180 days)."
  type        = number
  default     = 365
}

variable "adot" {
  description = "ADOT collector sidecar: scrapes the management port into Amazon Managed Prometheus, forwards traces to X-Ray."
  type = object({
    image                = string
    config               = string
    amp_workspace_arn    = string
    amp_region           = string
    amp_remote_write_url = string
  })
  default = null
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
