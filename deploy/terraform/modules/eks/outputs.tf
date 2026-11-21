output "cluster_name" {
  description = "Cluster name"
  value       = module.eks.cluster_name
}

output "cluster_arn" {
  description = "Cluster ARN"
  value       = module.eks.cluster_arn
}

output "cluster_endpoint" {
  description = "API server endpoint"
  value       = module.eks.cluster_endpoint
}

output "node_security_group_id" {
  description = "Security group created by the EKS module for control-plane/node traffic"
  value       = module.eks.node_security_group_id
}
