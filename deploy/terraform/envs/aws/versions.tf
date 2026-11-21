terraform {
  required_version = "= 1.17.2"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.73.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "= 3.9.1"
    }
  }

  # Remote state holds generated passwords, so the bucket must be encrypted,
  # versioned and restricted to the platform owners. Enable after creating it,
  # then run `terraform init` without -backend=false.
  #
  # backend "s3" {
  #   bucket       = "<owner-state-bucket>"
  #   key          = "sensor-platform/aws/terraform.tfstate"
  #   region       = "us-east-1"
  #   encrypt      = true
  #   kms_key_id   = "alias/<state-key>"
  #   use_lockfile = true
  # }
}
