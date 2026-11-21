variable "endpoint" {
  description = "AWS Emulator endpoint as the Terraform container sees it"
  type        = string
  default     = "https://127.0.0.1:9297"
}

variable "insecure" {
  description = "Skip TLS verification (needed when the endpoint host is not in the emulator certificate SANs)"
  type        = bool
  default     = false
}

variable "access_key" {
  type    = string
  default = "test"
}

variable "secret_key" {
  type      = string
  default   = "test"
  sensitive = true
}

provider "aws" {
  region     = "us-east-1"
  access_key = var.access_key
  secret_key = var.secret_key
  insecure   = var.insecure
  # CA comes from AWS_CA_BUNDLE (or custom_ca_bundle) when insecure = false.

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  skip_region_validation      = true

  endpoints {
    ec2            = var.endpoint
    iam            = var.endpoint
    kms            = var.endpoint
    secretsmanager = var.endpoint
    ecr            = var.endpoint
    sts            = var.endpoint
  }
}

resource "aws_vpc" "main" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "sensor-spike" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  availability_zone = "us-east-1${count.index == 0 ? "a" : "b"}"
  tags              = { Name = "sensor-spike-private-${count.index}" }
}

resource "aws_security_group" "app" {
  name        = "sensor-spike-app"
  description = "spike app"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "10.20.0.0/16"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_iam_role" "app" {
  name = "sensor-spike-app"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_policy" "read_secret" {
  name = "sensor-spike-read-secret"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = aws_secretsmanager_secret.db.arn
    }]
  })
}

resource "aws_iam_role_policy_attachment" "read_secret" {
  role       = aws_iam_role.app.name
  policy_arn = aws_iam_policy.read_secret.arn
}

resource "aws_kms_key" "main" {
  description             = "sensor-spike"
  deletion_window_in_days = 7
}

resource "aws_kms_alias" "main" {
  name          = "alias/sensor-spike"
  target_key_id = aws_kms_key.main.key_id
}

resource "aws_secretsmanager_secret" "db" {
  name                    = "sensor-spike/db"
  kms_key_id              = aws_kms_key.main.arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id     = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({ username = "sensor", password = "spike-dummy-pw" })
}

resource "aws_ecr_repository" "app" {
  name         = "sensor-spike/app"
  force_delete = true
}

output "secret_name" { value = aws_secretsmanager_secret.db.name }
output "ecr_repository_url" { value = aws_ecr_repository.app.repository_url }
output "vpc_id" { value = aws_vpc.main.id }
