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

variable "availability_zone" {
  description = "Availability Zone for the public subnet. Change this if G6 capacity is unavailable."
  type        = string
  default     = "ap-northeast-2a"
}

variable "vpc_cidr" {
  description = "VPC IPv4 CIDR"
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidr" {
  description = "Public subnet IPv4 CIDR"
  type        = string
  default     = "10.20.16.0/20"
}
