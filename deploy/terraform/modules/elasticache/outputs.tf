output "primary_endpoint_address" {
  description = "Primary endpoint host name"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "reader_endpoint_address" {
  description = "Reader endpoint host name"
  value       = aws_elasticache_replication_group.this.reader_endpoint_address
}

output "port" {
  description = "TLS port"
  value       = aws_elasticache_replication_group.this.port
}
