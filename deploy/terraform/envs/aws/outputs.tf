output "kms_key_arn" {
  description = "Platform KMS key ARN"
  value       = module.platform.kms_key_arn
}

output "secret_names" {
  description = "Secrets Manager names by short key"
  value       = module.platform.secret_names
}

output "secret_arns" {
  description = "Secrets Manager ARNs by short key"
  value       = module.platform.secret_arns
}

output "ecr_repository_urls" {
  description = "ECR repository URLs"
  value       = module.platform.ecr_repository_urls
}

output "tempo_bucket_name" {
  description = "Tempo traces bucket"
  value       = module.platform.tempo_bucket_name
}

output "iam_role_arns" {
  description = "Pod Identity role ARNs"
  value       = module.platform.iam_role_arns
}

output "security_group_ids" {
  description = "Security group ids (the ALB group goes into the Ingress annotation)"
  value       = module.platform.security_group_ids
}

output "public_subnet_ids" {
  description = "Public subnet ids"
  value       = module.platform.public_subnet_ids
}

output "eks_cluster_name" {
  description = "EKS cluster name"
  value       = module.platform.eks_cluster_name
}

output "eks_cluster_endpoint" {
  description = "EKS API endpoint"
  value       = module.platform.eks_cluster_endpoint
}

output "rds_address" {
  description = "RDS writer host"
  value       = module.platform.rds_address
}

output "rds_master_secret_arn" {
  description = "RDS-managed master secret ARN"
  value       = module.platform.rds_master_secret_arn
}

output "redis_primary_endpoint" {
  description = "ElastiCache primary endpoint"
  value       = module.platform.redis_primary_endpoint
}

output "msk_bootstrap_brokers_sasl_scram" {
  description = "MSK SASL/SCRAM bootstrap brokers"
  value       = module.platform.msk_bootstrap_brokers_sasl_scram
}

output "acm_certificate_arn" {
  description = "ALB certificate ARN (pending until its DNS validation records exist)"
  value       = aws_acm_certificate.alb.arn
}

output "acm_validation_records" {
  description = "DNS records to create to validate the certificate"
  value = [for o in aws_acm_certificate.alb.domain_validation_options : {
    name  = o.resource_record_name
    type  = o.resource_record_type
    value = o.resource_record_value
  }]
}

output "vpc_id" {
  description = "VPC id (deploy/environments/aws.yaml vpcId, used by the load balancer controller)"
  value       = module.platform.vpc_id
}
