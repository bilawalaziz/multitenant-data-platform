output "role_arns" {
  description = "Role ARN by add-on: external-secrets, tempo, aws-load-balancer-controller"
  value       = { for k, r in aws_iam_role.this : k => r.arn }
}

output "policy_arns" {
  description = "Policy ARN by add-on"
  value       = { for k, p in aws_iam_policy.this : k => p.arn }
}

output "pod_identity_associations" {
  description = "Input for the eks module: service account to role bindings"
  value = {
    external-secrets = {
      namespace       = var.service_accounts.external_secrets.namespace
      service_account = var.service_accounts.external_secrets.name
      role_arn        = aws_iam_role.this["external-secrets"].arn
    }
    aws-load-balancer-controller = {
      namespace       = var.service_accounts.lb_controller.namespace
      service_account = var.service_accounts.lb_controller.name
      role_arn        = aws_iam_role.this["aws-load-balancer-controller"].arn
    }
    tempo = {
      namespace       = var.service_accounts.tempo.namespace
      service_account = var.service_accounts.tempo.name
      role_arn        = aws_iam_role.this["tempo"].arn
    }
  }
}
