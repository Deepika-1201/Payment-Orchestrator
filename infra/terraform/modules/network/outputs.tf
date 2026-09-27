output "vpc_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Subnets for the load balancer and NAT gateways."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Subnets for ECS tasks (egress through NAT)."
  value       = aws_subnet.private[*].id
}

output "database_subnet_ids" {
  description = "Isolated subnets for Aurora."
  value       = aws_subnet.database[*].id
}

output "nat_public_ips" {
  description = "Static egress addresses to give PSPs for their allowlists."
  value       = aws_eip.nat[*].public_ip
}
