output "address" {
  description = "Writer endpoint host name"
  value       = aws_db_instance.this.address
}

output "port" {
  description = "PostgreSQL port"
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "Application database name"
  value       = aws_db_instance.this.db_name
}

output "master_user_secret_arn" {
  description = "RDS-managed master secret (admin use only, outside the sensor-platform/ prefix)"
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
