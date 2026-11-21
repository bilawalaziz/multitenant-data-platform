# Passwords are generated, never supplied. They end up in Terraform state, so
# the state backend must be encrypted and access-controlled.
# Letters and digits only: valid for Redis AUTH, MSK SCRAM and URL-embedded DB passwords.
resource "random_password" "this" {
  for_each = toset(["db_app", "db_owner", "db_keycloak", "redis_auth", "kafka_app", "keycloak_admin", "keycloak_provisioner"])
  length   = 40
  special  = false
}

locals {
  secret_values = {
    "db/app" = jsonencode({
      username = var.db_app_username
      password = random_password.this["db_app"].result
      dbname   = var.db_name
    })
    "db/owner" = jsonencode({
      username = var.db_owner_username
      password = random_password.this["db_owner"].result
      dbname   = var.db_name
    })
    "db/keycloak" = jsonencode({
      username = var.db_keycloak_username
      password = random_password.this["db_keycloak"].result
      dbname   = "keycloak"
    })
    "redis/auth" = jsonencode({
      auth_token = random_password.this["redis_auth"].result
    })
    "kafka/app" = jsonencode({
      username  = var.kafka_username
      password  = random_password.this["kafka_app"].result
      mechanism = "SCRAM-SHA-512"
    })
    "keycloak/admin" = jsonencode({
      username = var.keycloak_admin_username
      password = random_password.this["keycloak_admin"].result
    })
    "keycloak/provisioner" = jsonencode({
      client_id     = var.keycloak_provisioner_client_id
      client_secret = random_password.this["keycloak_provisioner"].result
    })
  }

  secret_names = toset(["db/app", "db/owner", "db/keycloak", "redis/auth", "kafka/app", "keycloak/admin", "keycloak/provisioner"])

  # MSK only associates secrets whose name starts with AmazonMSK_. It carries the
  # same credentials as kafka/app, which is the copy the pods read.
  msk_secret_name = "AmazonMSK_${var.name_prefix}"
}

resource "aws_secretsmanager_secret" "this" {
  for_each                = local.secret_names
  name                    = "${var.name_prefix}/${each.value}"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "this" {
  for_each      = local.secret_names
  secret_id     = aws_secretsmanager_secret.this[each.value].id
  secret_string = local.secret_values[each.value]
}

resource "aws_secretsmanager_secret" "msk_scram" {
  name                    = local.msk_secret_name
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "msk_scram" {
  secret_id = aws_secretsmanager_secret.msk_scram.id
  secret_string = jsonencode({
    username = var.kafka_username
    password = random_password.this["kafka_app"].result
  })
}
