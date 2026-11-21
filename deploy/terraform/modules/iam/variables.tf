variable "name_prefix" {
  description = "Prefix for role and policy names"
  type        = string
  default     = "sensor-platform"
}

variable "secrets_prefix" {
  description = "Secrets Manager name prefix External Secrets may read"
  type        = string
  default     = "sensor-platform"
}

variable "kms_key_arn" {
  description = "Platform KMS key ARN; account and region for the policy ARNs are taken from it"
  type        = string
}

variable "tempo_bucket_arn" {
  description = "ARN of the Tempo traces bucket"
  type        = string
}

variable "service_accounts" {
  description = "Namespace and service account each role is bound to through EKS Pod Identity"
  type = object({
    external_secrets = object({ namespace = string, name = string })
    lb_controller    = object({ namespace = string, name = string })
    tempo            = object({ namespace = string, name = string })
  })
  default = {
    external_secrets = { namespace = "external-secrets", name = "external-secrets" }
    lb_controller    = { namespace = "kube-system", name = "aws-load-balancer-controller" }
    tempo            = { namespace = "observability", name = "tempo" }
  }
}
