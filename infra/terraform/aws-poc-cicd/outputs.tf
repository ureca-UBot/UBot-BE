output "ecr_repository_name" {
  value = aws_ecr_repository.be.name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.be.repository_url
}

output "github_publish_role_arn" {
  value = aws_iam_role.github_publish.arn
}

output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}

output "ssm_document_name" {
  value = aws_ssm_document.be_deploy.name
}

output "github_oidc_provider_arn" {
  value = local.github_oidc_provider_arn
}
