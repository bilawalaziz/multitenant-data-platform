variable "name" {
  description = "Cluster name"
  type        = string
}

variable "data_subnet_ids" {
  description = "Three private data subnets, one per AZ"
  type        = list(string)
}

variable "security_group_id" {
  description = "MSK security group (9096 from the nodes only)"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key for encryption at rest"
  type        = string
}

variable "scram_secret_arn" {
  description = "AmazonMSK_ secret (customer-managed KMS key) with the SCRAM credentials"
  type        = string
}

variable "kafka_version" {
  type    = string
  default = "3.9.x"
}

variable "broker_instance_type" {
  type    = string
  default = "kafka.m7g.large"
}

variable "broker_volume_size" {
  description = "EBS volume per broker in GiB"
  type        = number
  default     = 100
}

variable "log_retention_days" {
  type    = number
  default = 90
}
