variable "name" {
  description = "Prefix for key aliases, for example pg-prod-mum."
  type        = string
}

variable "tags" {
  description = "Tags for every resource."
  type        = map(string)
  default     = {}
}
