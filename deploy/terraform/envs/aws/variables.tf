variable "region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "domain_name" {
  description = "Public host name served by the ALB (certificate subject), for example sensors.example.com"
  type        = string
}

variable "tempo_bucket_name" {
  description = "Globally unique name of the Tempo traces bucket"
  type        = string
}

variable "eks_endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach the EKS API; empty keeps the endpoint private to the VPC"
  type        = list(string)
  default     = []
}
