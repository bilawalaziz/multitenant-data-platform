variable "bucket_name" {
  description = "Globally unique bucket name"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key used for default bucket encryption"
  type        = string
}

variable "expiration_days" {
  description = "Days after which objects expire"
  type        = number
  default     = 14
}

variable "force_destroy" {
  description = "Allow destroying a non-empty bucket"
  type        = bool
  default     = false
}
