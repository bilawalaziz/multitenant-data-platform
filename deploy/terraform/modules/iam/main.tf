# EKS Pod Identity roles for the three cluster add-ons that call AWS. The app and
# consumer service accounts get no role on purpose: they read secrets that
# External Secrets copies into the cluster and talk to data services over the network.
locals {
  # arn:aws:kms:<region>:<account>:key/<id>
  region     = split(":", var.kms_key_arn)[3]
  account_id = split(":", var.kms_key_arn)[4]

  # Only EKS Pod Identity in this account may assume the roles (confused-deputy guard).
  pod_identity_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Condition = { StringEquals = { "aws:SourceAccount" = local.account_id } }
    }]
  })

  # RDS names its managed master secret rds!db-<id>, outside the platform prefix; the
  # database bootstrap job needs it, so it is granted by exact ARN.
  extra_secret_statements = length(var.extra_secret_arns) == 0 ? [] : [{
    Sid      = "ReadNamedSecrets"
    Effect   = "Allow"
    Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    Resource = var.extra_secret_arns
  }]

  policies = {
    external-secrets = jsonencode({
      Version = "2012-10-17"
      Statement = concat([
        {
          Sid      = "ReadPlatformSecrets"
          Effect   = "Allow"
          Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
          Resource = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:${var.secrets_prefix}/*"
        },
        {
          Sid       = "DecryptPlatformKey"
          Effect    = "Allow"
          Action    = ["kms:Decrypt"]
          Resource  = var.kms_key_arn
          Condition = { StringEquals = { "kms:ViaService" = "secretsmanager.${local.region}.amazonaws.com" } }
        }
      ], local.extra_secret_statements)
    })

    tempo = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid      = "ListTracesBucket"
          Effect   = "Allow"
          Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
          Resource = var.tempo_bucket_arn
        },
        {
          Sid      = "ReadWriteTraceObjects"
          Effect   = "Allow"
          Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
          Resource = "${var.tempo_bucket_arn}/*"
        },
        {
          Sid       = "UsePlatformKeyForBucket"
          Effect    = "Allow"
          Action    = ["kms:Decrypt", "kms:GenerateDataKey"]
          Resource  = var.kms_key_arn
          Condition = { StringEquals = { "kms:ViaService" = "s3.${local.region}.amazonaws.com" } }
        }
      ]
    })

    # Official policy of aws-load-balancer-controller v3.6.3, unmodified.
    aws-load-balancer-controller = file("${path.module}/aws-load-balancer-controller-policy.json")
  }

  roles = toset(keys(local.policies))
}

resource "aws_iam_role" "this" {
  for_each           = local.roles
  name               = "${var.name_prefix}-${each.value}"
  assume_role_policy = local.pod_identity_trust
}

resource "aws_iam_policy" "this" {
  for_each = local.roles
  name     = "${var.name_prefix}-${each.value}"
  policy   = local.policies[each.value]
}

resource "aws_iam_role_policy_attachment" "this" {
  for_each   = local.roles
  role       = aws_iam_role.this[each.value].name
  policy_arn = aws_iam_policy.this[each.value].arn
}
