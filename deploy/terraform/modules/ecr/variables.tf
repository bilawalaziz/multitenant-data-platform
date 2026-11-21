variable "repository_names" {
  description = "Full repository names, for example sensor-platform/app"
  type        = list(string)
}

variable "kms_key_arn" {
  description = "KMS key for repository encryption"
  type        = string
}

variable "retained_image_count" {
  description = "Tagged images kept per repository"
  type        = number
  default     = 30
}

variable "untagged_expiry_days" {
  description = "Days before untagged images expire"
  type        = number
  default     = 14
}

variable "force_delete" {
  description = "Delete repositories that still hold images"
  type        = bool
  default     = false
}
