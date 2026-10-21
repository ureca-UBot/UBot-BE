variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project_name" {
  type    = string
  default = "ubot"
}

variable "github_repository" {
  description = "Organization/repository allowed to publish and deploy."
  type        = string
  default     = "ureca-UBot/UBot-BE"
}

variable "github_publish_subject" {
  description = "Override the exact OIDC sub claim if this repository uses immutable subject claims."
  type        = string
  default     = null
}

variable "github_deploy_subject" {
  description = "Override the exact OIDC sub claim for the protected deployment environment."
  type        = string
  default     = null
}

variable "github_oidc_provider_arn" {
  description = "Existing GitHub Actions OIDC provider ARN. If null, this stack creates one."
  type        = string
  default     = null
}

variable "be_instance_id" {
  description = "The existing BE EC2 instance ID. This stack never creates or replaces the instance."
  type        = string
}

variable "be_instance_role_name" {
  description = "Name of the existing BE EC2 instance IAM role for ECR pull access."
  type        = string
}

variable "be_checkout_path" {
  description = "Existing BE directory on the instance containing the private .env; CD creates versioned releases beneath it."
  type        = string
  default     = "/home/ssm-user/ubot-api"
}
