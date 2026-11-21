module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.30.2"

  name               = var.cluster_name
  kubernetes_version = var.kubernetes_version

  vpc_id     = var.vpc_id
  subnet_ids = var.app_subnet_ids

  endpoint_public_access       = var.endpoint_public_access
  endpoint_public_access_cidrs = var.endpoint_public_access_cidrs

  # The platform key already exists and is shared; do not create a second one.
  create_kms_key = false
  encryption_config = {
    provider_key_arn = var.kms_key_arn
    resources        = ["secrets"]
  }

  enabled_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  enable_cluster_creator_admin_permissions = true

  addons = {
    coredns                = {}
    kube-proxy             = {}
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
  }

  eks_managed_node_groups = {
    default = {
      ami_type       = "AL2023_x86_64_STANDARD"
      instance_types = var.node_instance_types
      min_size       = var.node_min_size
      max_size       = var.node_max_size
      desired_size   = var.node_desired_size

      # Source group of the RDS, ElastiCache and MSK rules.
      vpc_security_group_ids = [var.nodes_security_group_id]

      # AWS-managed EBS key: a customer key would need grants for the Auto Scaling service-linked role.
      block_device_mappings = {
        xvda = {
          device_name = "/dev/xvda"
          ebs = {
            volume_size           = 50
            volume_type           = "gp3"
            encrypted             = true
            delete_on_termination = true
          }
        }
      }
    }
  }
}

resource "aws_eks_pod_identity_association" "this" {
  for_each        = var.pod_identity_associations
  cluster_name    = module.eks.cluster_name
  namespace       = each.value.namespace
  service_account = each.value.service_account
  role_arn        = each.value.role_arn
}
