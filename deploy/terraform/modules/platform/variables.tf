variable "name" {
  description = "Name prefix for every resource"
  type        = string
  default     = "sensor-platform"
}

variable "azs" {
  description = "Exactly three availability zones"
  type        = list(string)
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

# ---- Feature flags: the only difference between the aws and local environments.

variable "create_eks" {
  description = "EKS cluster, node group and Pod Identity associations"
  type        = bool
}

variable "create_data_services" {
  description = "RDS, ElastiCache and MSK"
  type        = bool
}

variable "create_nat" {
  description = "NAT gateways for the private app subnets"
  type        = bool
}

# ---- Lifecycle settings (safe defaults; local overrides them so destroy is clean).

variable "kms_deletion_window_in_days" {
  type    = number
  default = 30
}

variable "secret_recovery_window_in_days" {
  type    = number
  default = 30
}

variable "force_delete" {
  description = "Delete ECR repositories and the Tempo bucket even when they hold data"
  type        = bool
  default     = false
}

variable "rds_deletion_protection" {
  type    = bool
  default = true
}

variable "single_nat_gateway" {
  type    = bool
  default = false
}

# ---- Application layout

variable "app_port" {
  type    = number
  default = 8080
}

variable "keycloak_port" {
  type    = number
  default = 8080
}

variable "tempo_bucket_name" {
  description = "Globally unique name of the Tempo traces bucket"
  type        = string
}

variable "tempo_retention_days" {
  type    = number
  default = 14
}

# ---- EKS

variable "kubernetes_version" {
  type    = string
  default = "1.34"
}

variable "eks_endpoint_public_access" {
  type    = bool
  default = false
}

variable "eks_endpoint_public_access_cidrs" {
  type    = list(string)
  default = []
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m7i.large"]
}

variable "node_desired_size" {
  type    = number
  default = 3
}
