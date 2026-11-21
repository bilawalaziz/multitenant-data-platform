variable "cluster_name" {
  description = "EKS cluster name"
  type        = string
}

variable "kubernetes_version" {
  type    = string
  default = "1.34"
}

variable "vpc_id" {
  description = "VPC id"
  type        = string
}

variable "app_subnet_ids" {
  description = "Private app subnets for the control plane ENIs and the node group"
  type        = list(string)
}

variable "nodes_security_group_id" {
  description = "Extra security group attached to the nodes (source of the data-tier rules)"
  type        = string
}

variable "kms_key_arn" {
  description = "Platform key used for Kubernetes secrets envelope encryption"
  type        = string
}

variable "endpoint_public_access" {
  description = "Expose the API server publicly; false keeps it reachable from inside the VPC only"
  type        = bool
  default     = false
}

variable "endpoint_public_access_cidrs" {
  description = "Who may reach the public API endpoint when it is enabled"
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m7i.large"]
}

variable "node_min_size" {
  type    = number
  default = 3
}

variable "node_max_size" {
  type    = number
  default = 6
}

variable "node_desired_size" {
  type    = number
  default = 3
}

variable "pod_identity_associations" {
  description = "Service account to IAM role bindings (name => namespace, service_account, role_arn)"
  type = map(object({
    namespace       = string
    service_account = string
    role_arn        = string
  }))
  default = {}
}
