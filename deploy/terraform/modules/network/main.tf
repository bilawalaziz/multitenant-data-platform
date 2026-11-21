# Plain resources rather than terraform-aws-modules/vpc: one code path for both
# environments, and the subnet tiers and security group matrix are specific to
# this platform.
locals {
  az_index  = { for i, az in var.azs : az => i }
  nat_count = var.create_nat ? (var.single_nat_gateway ? 1 : length(var.azs)) : 0
  # Traffic ports plus the health-check ports the ALB target groups probe (app and Keycloak management).
  alb_to_nodes_ports = toset(concat(
    [tostring(var.app_port), tostring(var.keycloak_port)],
    [for p in var.health_check_ports : tostring(p)],
  ))
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name }
}

# Public tier: ALB and NAT only (/24 per AZ).
resource "aws_subnet" "public" {
  for_each                = local.az_index
  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, each.value)
  map_public_ip_on_launch = false
  tags = {
    Name                     = "${var.name}-public-${each.key}"
    "kubernetes.io/role/elb" = "1"
  }
}

# App tier: EKS nodes and pods. Larger (/20) because the VPC CNI hands out pod IPs from here.
resource "aws_subnet" "app" {
  for_each          = local.az_index
  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 1 + each.value)
  tags = {
    Name                                        = "${var.name}-app-${each.key}"
    "kubernetes.io/role/internal-elb"           = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

# Data tier: RDS, ElastiCache, MSK. No route to the internet.
resource "aws_subnet" "data" {
  for_each          = local.az_index
  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 100 + each.value)
  tags              = { Name = "${var.name}-data-${each.key}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count  = local.nat_count
  domain = "vpc"
  tags   = { Name = "${var.name}-nat-${count.index}" }
}

resource "aws_nat_gateway" "this" {
  count         = local.nat_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[var.azs[count.index]].id
  tags          = { Name = "${var.name}-${count.index}" }
  depends_on    = [aws_internet_gateway.this]
}

# One route table per AZ so each AZ can use its own NAT gateway.
resource "aws_route_table" "app" {
  for_each = local.az_index
  vpc_id   = aws_vpc.this.id
  tags     = { Name = "${var.name}-app-${each.key}" }
}

resource "aws_route" "app_nat" {
  for_each               = var.create_nat ? local.az_index : {}
  route_table_id         = aws_route_table.app[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.single_nat_gateway ? 0 : each.value].id
}

resource "aws_route_table_association" "app" {
  for_each       = aws_subnet.app
  subnet_id      = each.value.id
  route_table_id = aws_route_table.app[each.key].id
}

resource "aws_route_table" "data" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-data" }
}

resource "aws_route_table_association" "data" {
  for_each       = aws_subnet.data
  subnet_id      = each.value.id
  route_table_id = aws_route_table.data.id
}

# ---- Security groups. Rules are separate resources so the groups can reference each other.

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Public ALB for the app and Keycloak"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-alb" }
}

resource "aws_security_group" "nodes" {
  name        = "${var.name}-nodes"
  description = "EKS worker nodes: extra group attached to the managed node group"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-nodes" }
}

resource "aws_security_group" "rds" {
  name        = "${var.name}-rds"
  description = "RDS PostgreSQL"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-rds" }
}

resource "aws_security_group" "redis" {
  name        = "${var.name}-redis"
  description = "ElastiCache"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-redis" }
}

resource "aws_security_group" "msk" {
  name        = "${var.name}-msk"
  description = "MSK brokers"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-msk" }
}

# Rules use aws_security_group_rule: the aws_vpc_security_group_*_rule resources
# read cross-group references back with an account prefix on the emulator, so the
# local plan never converges.
#
# The ALB is the only internet-facing entry point. Port 80 only redirects to 443.
# Intentionally public: internet-facing ALB must accept HTTPS from anywhere
resource "aws_security_group_rule" "alb_https" {
  type              = "ingress"
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"
  cidr_blocks       = ["0.0.0.0/0"]
  protocol          = "tcp"
  from_port         = 443
  to_port           = 443
}

# Intentionally public: internet-facing ALB redirects HTTP to HTTPS
resource "aws_security_group_rule" "alb_http" {
  type              = "ingress"
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the internet (redirect to HTTPS)"
  cidr_blocks       = ["0.0.0.0/0"]
  protocol          = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_security_group_rule" "alb_to_nodes" {
  type                     = "egress"
  for_each                 = local.alb_to_nodes_ports
  security_group_id        = aws_security_group.alb.id
  description              = "ALB to pods on ${each.value}"
  source_security_group_id = aws_security_group.nodes.id
  protocol                 = "tcp"
  from_port                = tonumber(each.value)
  to_port                  = tonumber(each.value)
}

resource "aws_security_group_rule" "nodes_from_alb" {
  type                     = "ingress"
  for_each                 = local.alb_to_nodes_ports
  security_group_id        = aws_security_group.nodes.id
  description              = "ALB to pods on ${each.value}"
  source_security_group_id = aws_security_group.alb.id
  protocol                 = "tcp"
  from_port                = tonumber(each.value)
  to_port                  = tonumber(each.value)
}

# Nodes reach the data tier on exactly one port per service. Their general
# egress (ECR, STS, DNS, ...) comes from the EKS module's own node group.
resource "aws_security_group_rule" "nodes_to_rds" {
  type                     = "egress"
  security_group_id        = aws_security_group.nodes.id
  description              = "PostgreSQL"
  source_security_group_id = aws_security_group.rds.id
  protocol                 = "tcp"
  from_port                = 5432
  to_port                  = 5432
}

resource "aws_security_group_rule" "nodes_to_redis" {
  type                     = "egress"
  security_group_id        = aws_security_group.nodes.id
  description              = "ElastiCache TLS"
  source_security_group_id = aws_security_group.redis.id
  protocol                 = "tcp"
  from_port                = 6379
  to_port                  = 6379
}

resource "aws_security_group_rule" "nodes_to_msk" {
  type                     = "egress"
  security_group_id        = aws_security_group.nodes.id
  description              = "MSK SASL/SCRAM over TLS"
  source_security_group_id = aws_security_group.msk.id
  protocol                 = "tcp"
  from_port                = 9096
  to_port                  = 9096
}

resource "aws_security_group_rule" "rds_from_nodes" {
  type                     = "ingress"
  security_group_id        = aws_security_group.rds.id
  description              = "PostgreSQL from nodes"
  source_security_group_id = aws_security_group.nodes.id
  protocol                 = "tcp"
  from_port                = 5432
  to_port                  = 5432
}

resource "aws_security_group_rule" "redis_from_nodes" {
  type                     = "ingress"
  security_group_id        = aws_security_group.redis.id
  description              = "ElastiCache TLS from nodes"
  source_security_group_id = aws_security_group.nodes.id
  protocol                 = "tcp"
  from_port                = 6379
  to_port                  = 6379
}

resource "aws_security_group_rule" "msk_from_nodes" {
  type                     = "ingress"
  security_group_id        = aws_security_group.msk.id
  description              = "SASL/SCRAM over TLS from nodes"
  source_security_group_id = aws_security_group.nodes.id
  protocol                 = "tcp"
  from_port                = 9096
  to_port                  = 9096
}
