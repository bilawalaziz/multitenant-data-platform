# Composition shared by every environment. Environments differ only in the
# provider setup and the feature flags they pass in.
locals {
  cluster_name = var.name
}

module "kms" {
  source                  = "../kms"
  name                    = var.name
  deletion_window_in_days = var.kms_deletion_window_in_days
}

module "network" {
  source             = "../network"
  name               = var.name
  cluster_name       = local.cluster_name
  vpc_cidr           = var.vpc_cidr
  azs                = var.azs
  create_nat         = var.create_nat
  single_nat_gateway = var.single_nat_gateway
  app_port           = var.app_port
  keycloak_port      = var.keycloak_port
}

module "secrets" {
  source                  = "../secrets"
  name_prefix             = var.name
  kms_key_arn             = module.kms.key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

module "ecr" {
  source           = "../ecr"
  repository_names = ["${var.name}/app", "${var.name}/consumer"]
  kms_key_arn      = module.kms.key_arn
  force_delete     = var.force_delete
}

module "s3" {
  source          = "../s3"
  bucket_name     = var.tempo_bucket_name
  kms_key_arn     = module.kms.key_arn
  expiration_days = var.tempo_retention_days
  force_destroy   = var.force_delete
}

module "iam" {
  source           = "../iam"
  name_prefix      = var.name
  secrets_prefix   = var.name
  kms_key_arn      = module.kms.key_arn
  tempo_bucket_arn = module.s3.bucket_arn
}

module "eks" {
  count  = var.create_eks ? 1 : 0
  source = "../eks"

  cluster_name                 = local.cluster_name
  kubernetes_version           = var.kubernetes_version
  vpc_id                       = module.network.vpc_id
  app_subnet_ids               = module.network.app_subnet_ids
  nodes_security_group_id      = module.network.security_group_ids["nodes"]
  kms_key_arn                  = module.kms.key_arn
  endpoint_public_access       = var.eks_endpoint_public_access
  endpoint_public_access_cidrs = var.eks_endpoint_public_access_cidrs
  node_instance_types          = var.node_instance_types
  node_desired_size            = var.node_desired_size
  pod_identity_associations    = module.iam.pod_identity_associations
}

module "rds" {
  count  = var.create_data_services ? 1 : 0
  source = "../rds"

  name                = var.name
  data_subnet_ids     = module.network.data_subnet_ids
  security_group_id   = module.network.security_group_ids["rds"]
  kms_key_arn         = module.kms.key_arn
  deletion_protection = var.rds_deletion_protection
}

module "elasticache" {
  count  = var.create_data_services ? 1 : 0
  source = "../elasticache"

  name              = "${var.name}-redis"
  data_subnet_ids   = module.network.data_subnet_ids
  security_group_id = module.network.security_group_ids["redis"]
  kms_key_arn       = module.kms.key_arn
  auth_token        = module.secrets.redis_auth_token
}

module "msk" {
  count  = var.create_data_services ? 1 : 0
  source = "../msk"

  name              = var.name
  data_subnet_ids   = module.network.data_subnet_ids
  security_group_id = module.network.security_group_ids["msk"]
  kms_key_arn       = module.kms.key_arn
  scram_secret_arn  = module.secrets.msk_scram_secret_arn
}
