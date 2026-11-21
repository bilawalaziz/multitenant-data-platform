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

  # State stays out of git: .scratch/ is ignored. The path is relative to this directory.
  backend "local" {
    path = "../../../../.scratch/tf/local/terraform.tfstate"
  }
}
