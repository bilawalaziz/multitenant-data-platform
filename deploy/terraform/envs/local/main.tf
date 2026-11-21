# Emulator-only settings live here and nowhere else. Dummy credentials are
# accepted because the emulator runs with authentication off. TLS stays
# verified through AWS_CA_BUNDLE (never insecure = true).
provider "aws" {
  region     = "us-east-1"
  access_key = "test"
  secret_key = "test"

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  skip_region_validation      = true
  s3_use_path_style           = true

  endpoints {
    ec2            = var.endpoint
    iam            = var.endpoint
    kms            = var.endpoint
    secretsmanager = var.endpoint
    ecr            = var.endpoint
    sts            = var.endpoint
    s3             = var.endpoint
  }

  default_tags {
    tags = {
      project = "sensor-platform"
      env     = "local"
    }
  }
}

module "platform" {
  source = "../../modules/platform"

  azs               = ["us-east-1a", "us-east-1b", "us-east-1c"]
  tempo_bucket_name = "sensor-platform-tempo-local"

  # The emulator fakes EKS and the data services, and its NAT/EIP support is broken.
  create_eks           = false
  create_data_services = false
  create_nat           = false

  # Immediate, clean teardown for a throwaway environment.
  kms_deletion_window_in_days    = 7
  secret_recovery_window_in_days = 0
  force_delete                   = true
}
