variable "name" {
  description = "Identifier prefix"
  type        = string
}

variable "data_subnet_ids" {
  description = "Private data subnets for the DB subnet group"
  type        = list(string)
}

variable "security_group_id" {
  description = "RDS security group (5432 from the nodes only)"
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key for storage, the managed master secret and Performance Insights"
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL major version"
  type        = string
  default     = "17"
}

variable "instance_class" {
  type    = string
  default = "db.m7g.large"
}

variable "allocated_storage" {
  description = "Initial storage in GiB"
  type        = number
  default     = 100
}

variable "max_allocated_storage" {
  description = "Storage autoscaling ceiling in GiB"
  type        = number
  default     = 500
}

variable "db_name" {
  type    = string
  default = "sensor_platform"
}

variable "master_username" {
  description = "Admin user created by RDS; its password lives in an RDS-managed secret"
  type        = string
  default     = "rds_admin"
}

variable "backup_retention_days" {
  type    = number
  default = 14
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "performance_insights_enabled" {
  type    = bool
  default = true
}
