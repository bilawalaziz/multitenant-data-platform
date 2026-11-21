provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project = "sensor-platform"
      env     = "aws"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

module "platform" {
  source = "../../modules/platform"

  azs               = slice(data.aws_availability_zones.available.names, 0, 3)
  tempo_bucket_name = var.tempo_bucket_name

  create_eks           = true
  create_data_services = true
  create_nat           = true

  eks_endpoint_public_access       = length(var.eks_endpoint_public_access_cidrs) > 0
  eks_endpoint_public_access_cidrs = var.eks_endpoint_public_access_cidrs
}

# Certificate for the ALB listener. Validation is DNS-based and left to the
# domain owner: create the records from the output, then reference the issued ARN
# in the Ingress annotation.
resource "aws_acm_certificate" "alb" {
  domain_name = var.domain_name
  # The ingresses use sensor.<domain> and keycloak.<domain>.
  subject_alternative_names = ["*.${var.domain_name}"]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}
