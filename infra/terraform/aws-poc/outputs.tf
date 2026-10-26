output "ecr_repository_url" {
  description = "GitHub secret AWS_BE_ECR_REPOSITORY_URL (UBot-BE)"
  value       = aws_ecr_repository.be.repository_url
}

output "be_cd_role_arn" {
  description = "GitHub secret AWS_BE_ROLE_ARN (UBot-BE)"
  value       = aws_iam_role.be_cd.arn
}

output "fe_cd_role_arn" {
  description = "GitHub secret AWS_FE_ROLE_ARN (UBot-FE)"
  value       = aws_iam_role.fe_cd.arn
}

output "ai_nlb_dns_name" {
  description = "NLB_DNS in the BE server .env"
  value       = aws_lb.ai.dns_name
}

output "gpu_asg_name" {
  value = aws_autoscaling_group.gpu.name
}

output "gpu_on_demand_private_ip" {
  description = "GUARD_REVIEW_LLM_BASE_URL (http://<ip>:18175/v1) and GUARD_REVIEW_RUNTIME_BASE_URL (http://<ip>:18176) in the BE server .env"
  value       = one(aws_instance.gpu_on_demand[*].private_ip)
}

output "gpu_builder_instance_id" {
  description = "Target of the setup checks, finalize.sh and create-image while gpu_builder_enabled is true"
  value       = one(aws_instance.gpu_builder[*].id)
}

output "fe_public_ip" {
  description = "u-bot.cloud DNS A record"
  value       = aws_eip.fe.public_ip
}

output "fe_private_ip" {
  description = "FE_PRIVATE_IP in the BE server .env"
  value       = aws_instance.fe.private_ip
}

output "be_api_private_ip" {
  description = "/api/ upstream of the FE Nginx"
  value       = aws_instance.be_api.private_ip
}

output "state_private_ip" {
  description = "POSTGRES_HOST and REDIS_HOST in the BE server .env"
  value       = aws_instance.state.private_ip
}
