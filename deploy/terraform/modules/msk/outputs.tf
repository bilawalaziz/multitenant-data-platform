output "cluster_arn" {
  description = "Cluster ARN"
  value       = aws_msk_cluster.this.arn
}

output "bootstrap_brokers_sasl_scram" {
  description = "Bootstrap broker list for SASL/SCRAM over TLS (port 9096)"
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}
