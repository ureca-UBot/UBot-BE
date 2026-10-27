terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

# 리소스를 수동 생성 후 현재 상태 그대로 가져왔으므로 default_tags를 두지 않음
# default_tags를 추가하면 가져온 모든 리소스가 변경되므로 필요할 때 리소스별 태그를 추가함
provider "aws" {
  region = var.aws_region
}
