output "vpc_id" {
  description = "VPC id"
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR"
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnets in AZ order"
  value       = [for az in var.azs : aws_subnet.public[az].id]
}

output "app_subnet_ids" {
  description = "Private app subnets in AZ order"
  value       = [for az in var.azs : aws_subnet.app[az].id]
}

output "data_subnet_ids" {
  description = "Private data subnets in AZ order"
  value       = [for az in var.azs : aws_subnet.data[az].id]
}

output "security_group_ids" {
  description = "Security group ids: alb, nodes, rds, redis, msk"
  value = {
    alb   = aws_security_group.alb.id
    nodes = aws_security_group.nodes.id
    rds   = aws_security_group.rds.id
    redis = aws_security_group.redis.id
    msk   = aws_security_group.msk.id
  }
}

output "nat_gateway_ids" {
  description = "NAT gateway ids (empty when create_nat is false)"
  value       = aws_nat_gateway.this[*].id
}
