resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.data_subnet_ids
}

resource "aws_elasticache_parameter_group" "this" {
  name        = "${var.name}-valkey8"
  family      = "valkey8"
  description = "${var.name} parameters"

  # Dedup and rate-limit keys all carry a TTL; evict only those under memory pressure.
  parameter {
    name  = "maxmemory-policy"
    value = "volatile-ttl" # same as compose: only keys with a TTL (all of ours) are evicted, soonest first
  }
}

# Valkey is wire-compatible with the Redis 7 protocol the app's clients use.
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "${var.name} rate limits and consumer dedup"

  engine               = "valkey"
  engine_version       = "8.0"
  parameter_group_name = aws_elasticache_parameter_group.this.name
  node_type            = var.node_type
  port                 = 6379

  num_cache_clusters         = var.num_cache_clusters
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.security_group_id]

  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  auth_token                 = var.auth_token

  snapshot_retention_limit   = var.snapshot_retention_days
  auto_minor_version_upgrade = true
}
