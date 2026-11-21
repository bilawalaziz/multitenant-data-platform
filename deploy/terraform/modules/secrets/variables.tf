variable "kms_key_arn" {
  description = "Customer managed KMS key that encrypts every secret (MSK requires a CMK for SCRAM secrets)"
  type        = string
}

variable "name_prefix" {
  description = "Secret name prefix; the External Secrets policy is scoped to <prefix>/*"
  type        = string
  default     = "sensor-platform"
}

variable "recovery_window_in_days" {
  description = "Recovery window on delete (0 deletes immediately, otherwise 7-30)"
  type        = number
  default     = 30
}

variable "db_app_username" {
  description = "PostgreSQL role the app connects as"
  type        = string
  default     = "sensor_app"
}

variable "db_owner_username" {
  description = "PostgreSQL role that owns the schema and runs migrations"
  type        = string
  default     = "sensor_owner"
}

variable "db_keycloak_username" {
  description = "PostgreSQL role Keycloak connects as"
  type        = string
  default     = "keycloak"
}

variable "db_name" {
  description = "Application database name stored next to the credentials"
  type        = string
  default     = "sensor_platform"
}

variable "kafka_username" {
  description = "SCRAM user the app and consumer use against MSK"
  type        = string
  default     = "sensor_app"
}

variable "keycloak_admin_username" {
  description = "Keycloak bootstrap admin user"
  type        = string
  default     = "admin"
}

variable "keycloak_provisioner_client_id" {
  description = "Keycloak client the app uses to create per-device clients"
  type        = string
  default     = "sensor-platform-provisioner"
}
