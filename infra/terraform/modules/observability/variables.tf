variable "name" {
  description = "Workspace alias and topic prefix."
  type        = string
}

variable "rules_file" {
  description = "Prometheus rule file; the same one the local stack and promtool tests use (ADR-027)."
  type        = string
}

variable "kms_key_arn" {
  description = "Telemetry key for the workspace, its logs and the alert topics."
  type        = string
}

variable "log_retention_days" {
  description = "Workspace log retention."
  type        = number
  default     = 365
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
