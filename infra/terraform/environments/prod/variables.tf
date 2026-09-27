variable "account_id" {
  description = "AWS account for production; the providers refuse any other."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "account_id must be a 12-digit AWS account id."
  }
}

variable "primary_region" {
  description = "Primary region."
  type        = string
  default     = "ap-south-1"

  validation {
    condition     = contains(["ap-south-1", "ap-south-2"], var.primary_region)
    error_message = "Payment data must stay in India (NFR-15): use ap-south-1 (Mumbai) or ap-south-2 (Hyderabad)."
  }
}

variable "dr_region" {
  description = "Warm-standby region."
  type        = string
  default     = "ap-south-2"

  validation {
    condition     = contains(["ap-south-1", "ap-south-2"], var.dr_region)
    error_message = "Payment data must stay in India (NFR-15): use ap-south-1 (Mumbai) or ap-south-2 (Hyderabad)."
  }

  validation {
    condition     = var.dr_region != var.primary_region
    error_message = "The standby region must differ from the primary, or a regional outage takes both down."
  }
}

variable "image_tag" {
  description = "Immutable image tag to deploy (the git commit)."
  type        = string

  validation {
    condition     = var.image_tag != "latest" && length(var.image_tag) > 0
    error_message = "Deploy an immutable tag such as the git commit, never latest."
  }
}

variable "api_domain" {
  description = "Public host name of the API and hosted checkout, for example api.pay.example.com."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 zone for api_domain; when set, failover records point at the primary and standby load balancers."
  type        = string
  default     = null
}

variable "certificate_arns" {
  description = "ACM certificates for api_domain in each region."
  type = object({
    primary = string
    dr      = string
  })
}

variable "psp_webhook_sources" {
  description = "Per PSP code (no underscores), the published IPv4 ranges its webhooks come from. Feeds both the WAF and the application allowlist (ADR-022)."
  type        = map(list(string))

  validation {
    condition     = length(var.psp_webhook_sources) > 0 && alltrue([for cidrs in values(var.psp_webhook_sources) : length(cidrs) > 0])
    error_message = "List each PSP's webhook source ranges; an empty list would block its webhooks."
  }

  validation {
    condition     = alltrue([for provider in keys(var.psp_webhook_sources) : can(regex("^[A-Za-z0-9]+$", provider))])
    error_message = "PSP codes become environment variable names and must be letters and digits only."
  }
}

variable "admin_ipv4_cidrs" {
  description = "Operator networks (office, VPN) allowed to reach the admin API."
  type        = list(string)
}

variable "oidc" {
  description = "Company identity provider for admin single sign-on (ADR-023)."
  type = object({
    issuer   = string
    jwks_uri = string
    audience = string
  })
  default = null
}

variable "postgres_major_version" {
  description = "Aurora PostgreSQL major version; the newest minor is chosen at plan time."
  type        = string
  default     = "17"
}

variable "db_instance_class" {
  description = "Aurora instance class in both regions."
  type        = string
  default     = "db.r7g.large"
}

variable "db_password_version" {
  description = "Bump to generate and store new database role passwords (then ALTER ROLE with them, see ADR-028)."
  type        = number
  default     = 1
}
