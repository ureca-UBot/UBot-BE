output "instance_id" {
  description = "GPU test EC2 instance ID"
  value       = aws_instance.gpu_test.id
}

output "instance_type" {
  description = "GPU instance type"
  value       = aws_instance.gpu_test.instance_type
}

output "purchase_option" {
  description = "EC2 purchase option"
  value       = var.use_spot ? "spot" : "on-demand"
}

output "ami_id" {
  description = "AWS Deep Learning AMI ID used by the GPU test instance"
  value       = nonsensitive(data.aws_ssm_parameter.dlami_gpu_ubuntu_2404.value)
}

output "private_ip" {
  description = "GPU server private IPv4 address"
  value       = aws_instance.gpu_test.private_ip
}

output "public_ip" {
  description = "GPU server public IPv4 address"
  value       = var.create_eip ? aws_eip.gpu_test[0].public_ip : aws_instance.gpu_test.public_ip
}

output "llm_api_url" {
  description = "OpenAI-compatible base URL. Reachability still depends on llm_allowed_cidrs."
  value       = "http://${var.create_eip ? aws_eip.gpu_test[0].public_ip : aws_instance.gpu_test.public_ip}:${var.llm_port}/v1"
}

output "ssm_start_session_command" {
  description = "AWS CLI command for Session Manager access"
  value       = "aws ssm start-session --target ${aws_instance.gpu_test.id} --region ${var.aws_region}"
}

output "bootstrap_log_command" {
  description = "Run after connecting with SSM"
  value       = "sudo cat /var/log/ubot-bootstrap.log"
}
