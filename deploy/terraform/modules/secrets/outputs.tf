output "secret_names" {
  description = "Secret name by short key (db/app, redis/auth, ...)"
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.name }
}

output "secret_arns" {
  description = "Secret ARN by short key (db/app, redis/auth, ...)"
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.arn }
}

output "msk_scram_secret_arn" {
  description = "AmazonMSK_ secret to associate with the cluster; resolves after its version exists"
  value       = aws_secretsmanager_secret_version.msk_scram.secret_arn
}

output "msk_scram_secret_name" {
  description = "Name of the AmazonMSK_ secret"
  value       = aws_secretsmanager_secret.msk_scram.name
}

output "redis_auth_token" {
  description = "ElastiCache AUTH token (also stored in redis/auth)"
  value       = random_password.this["redis_auth"].result
  sensitive   = true
}
