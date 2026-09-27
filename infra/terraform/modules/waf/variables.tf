variable "name" {
  description = "Web ACL name."
  type        = string
}

variable "alb_arn" {
  description = "Load balancer to protect."
  type        = string
}

variable "psp_webhook_ipv4_cidrs" {
  description = "Published source ranges of every PSP that sends webhooks; all other sources are blocked on /v1/webhooks/providers/."
  type        = list(string)

  validation {
    condition     = length(var.psp_webhook_ipv4_cidrs) > 0 && alltrue([for cidr in var.psp_webhook_ipv4_cidrs : can(cidrhost(cidr, 0))])
    error_message = "List the PSPs' published webhook source ranges as IPv4 CIDRs; an empty list would block every PSP webhook."
  }
}

variable "psp_webhook_ipv6_cidrs" {
  description = "IPv6 source ranges of PSPs that send webhooks over IPv6."
  type        = list(string)
  default     = []
}

variable "admin_ipv4_cidrs" {
  description = "Operator networks (office, VPN) allowed to reach /admin/."
  type        = list(string)

  validation {
    condition     = length(var.admin_ipv4_cidrs) > 0 && alltrue([for cidr in var.admin_ipv4_cidrs : can(cidrhost(cidr, 0))])
    error_message = "List the operator networks as IPv4 CIDRs."
  }
}

variable "admin_countries" {
  description = "Countries (ISO 3166 alpha-2) the admin API may be used from."
  type        = list(string)
  default     = ["IN"]
}

variable "blocked_countries" {
  description = "Countries blocked for all traffic (for example under sanctions policy)."
  type        = list(string)
  default     = []
}

variable "rate_limit" {
  description = "Requests per client IP per 5 minutes before blocking (PSP webhooks excluded)."
  type        = number
  default     = 2000
}

variable "logs_kms_key_arn" {
  description = "Key for the WAF log group."
  type        = string
}

variable "log_retention_days" {
  description = "WAF log retention."
  type        = number
  default     = 365
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
