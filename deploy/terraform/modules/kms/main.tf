# One platform key for RDS, ElastiCache, MSK, S3, ECR, Secrets Manager and EKS
# secrets. The default key policy delegates access to IAM, so every consumer is
# granted through its own IAM policy.
resource "aws_kms_key" "this" {
  description             = "${var.name} platform encryption key"
  enable_key_rotation     = true
  deletion_window_in_days = var.deletion_window_in_days
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}"
  target_key_id = aws_kms_key.this.key_id
}
