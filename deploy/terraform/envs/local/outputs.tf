output "secret_names" {
  description = "Secrets Manager names by short key (db/app, redis/auth, kafka/app, ...)"
  value       = module.platform.secret_names
}

output "secret_arns" {
  description = "Secrets Manager ARNs by short key"
  value       = module.platform.secret_arns
}

output "msk_scram_secret_name" {
  description = "AmazonMSK_ secret name"
  value       = module.platform.msk_scram_secret_name
}

output "kms_key_arn" {
  description = "Platform KMS key ARN"
  value       = module.platform.kms_key_arn
}

output "ecr_repository_urls" {
  description = "ECR repository URLs by repository name"
  value       = module.platform.ecr_repository_urls
}

output "tempo_bucket_name" {
  description = "Tempo traces bucket"
  value       = module.platform.tempo_bucket_name
}

output "iam_role_arns" {
  description = "Pod Identity role ARNs (unused locally: ESO uses static dummy credentials)"
  value       = module.platform.iam_role_arns
}

output "vpc_id" {
  description = "VPC id"
  value       = module.platform.vpc_id
}

output "security_group_ids" {
  description = "Security group ids"
  value       = module.platform.security_group_ids
}
