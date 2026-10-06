variable "project_name" {
  description = "Project name used for resource naming"
  type        = string
  default     = "ubot"
}

variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-2"
}

variable "network_vpc_name" {
  description = "Name tag of the VPC created by the network stack"
  type        = string
  default     = "ubot-vpc"
}

variable "network_public_subnet_name" {
  description = "Name tag of the public subnet created by the network stack"
  type        = string
  default     = "ubot-public-subnet"
}

variable "gpu_instance_type" {
  description = "GPU inference test EC2 instance type"
  type        = string
  default     = "g6.xlarge"
}

variable "use_spot" {
  description = "Use EC2 Spot for the GPU test server"
  type        = bool
  default     = true
}

variable "spot_max_price" {
  description = "Optional maximum hourly Spot price. null means use AWS default behavior."
  type        = string
  default     = null
  nullable    = true
}

variable "root_volume_size" {
  description = "Root EBS volume size in GiB"
  type        = number
  default     = 100

  validation {
    condition     = var.root_volume_size >= 50
    error_message = "root_volume_size must be at least 50 GiB for GPU model/container testing."
  }
}

variable "root_volume_type" {
  description = "Root EBS volume type"
  type        = string
  default     = "gp3"
}

variable "root_volume_iops" {
  description = "Root gp3 volume IOPS"
  type        = number
  default     = 3000
}

variable "root_volume_throughput" {
  description = "Root gp3 volume throughput in MiB/s"
  type        = number
  default     = 125
}

variable "create_eip" {
  description = "Create and associate an Elastic IP"
  type        = bool
  default     = false
}

variable "llm_port" {
  description = "OpenAI-compatible inference API port"
  type        = number
  default     = 8000
}

variable "llm_allowed_cidrs" {
  description = "CIDRs allowed to access the LLM API. Empty by default; use /32 for a known backend/test IP."
  type        = list(string)
  default     = []
}
