variable "name" {
  description = "Name prefix for network resources"
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster name, used for subnet discovery tags"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR; public /24, app /20 and data /24 subnets are carved from it"
  type        = string
  default     = "10.20.0.0/16"
}

variable "azs" {
  description = "Exactly three availability zones"
  type        = list(string)

  validation {
    condition     = length(var.azs) == 3
    error_message = "The platform spans exactly three availability zones."
  }
}

variable "create_nat" {
  description = "Create NAT gateways and the default routes of the app subnets"
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway instead of one per availability zone"
  type        = bool
  default     = false
}

variable "app_port" {
  description = "Port the ingestion app listens on behind the ALB"
  type        = number
  default     = 8080
}

variable "keycloak_port" {
  description = "Port Keycloak listens on behind the ALB"
  type        = number
  default     = 8080
}
