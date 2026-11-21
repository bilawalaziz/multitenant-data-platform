output "kms_key_arn" {
  description = "Platform KMS key ARN"
  value       = module.kms.key_arn
}

output "kms_alias" {
  description = "Platform KMS key alias"
  value       = module.kms.alias_name
}

output "secret_names" {
  description = "Secrets Manager names by short key (db/app, redis/auth, kafka/app, ...)"
  value       = module.secrets.secret_names
}

output "secret_arns" {
  description = "Secrets Manager ARNs by short key"
  value       = module.secrets.secret_arns
}

output "msk_scram_secret_name" {
  description = "AmazonMSK_ secret associated with the cluster"
  value       = module.secrets.msk_scram_secret_name
}

output "ecr_repository_urls" {
  description = "ECR repository URL by repository name"
  value       = module.ecr.repository_urls
}

output "tempo_bucket_name" {
  description = "Tempo traces bucket"
  value       = module.s3.bucket_name
}

output "iam_role_arns" {
  description = "Pod Identity role ARNs: external-secrets, tempo, aws-load-balancer-controller"
  value       = module.iam.role_arns
}

output "vpc_id" {
  description = "VPC id"
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnet ids"
  value       = module.network.public_subnet_ids
}

output "app_subnet_ids" {
  description = "App subnet ids"
  value       = module.network.app_subnet_ids
}

output "data_subnet_ids" {
  description = "Data subnet ids"
  value       = module.network.data_subnet_ids
}

output "security_group_ids" {
  description = "Security group ids: alb, nodes, rds, redis, msk"
  value       = module.network.security_group_ids
}

output "eks_cluster_name" {
  description = "EKS cluster name (null when create_eks is false)"
  value       = one(module.eks[*].cluster_name)
}

output "eks_cluster_endpoint" {
  description = "EKS API endpoint (null when create_eks is false)"
  value       = one(module.eks[*].cluster_endpoint)
}

output "rds_address" {
  description = "RDS writer host (null when create_data_services is false)"
  value       = one(module.rds[*].address)
}

output "rds_master_secret_arn" {
  description = "RDS-managed master secret ARN (null when create_data_services is false)"
  value       = one(module.rds[*].master_user_secret_arn)
}

output "redis_primary_endpoint" {
  description = "ElastiCache primary endpoint (null when create_data_services is false)"
  value       = one(module.elasticache[*].primary_endpoint_address)
}

output "msk_bootstrap_brokers_sasl_scram" {
  description = "MSK SASL/SCRAM bootstrap brokers (null when create_data_services is false)"
  value       = one(module.msk[*].bootstrap_brokers_sasl_scram)
}
