data "aws_caller_identity" "current" {}

locals {
  account_id         = data.aws_caller_identity.current.account_id
  github_owner       = "${var.github_owner}@${var.github_owner_id}"
  be_oidc_repository = "repo:${local.github_owner}/${var.be_repository.name}@${var.be_repository.id}"
  fe_oidc_repository = "repo:${local.github_owner}/${var.fe_repository.name}@${var.fe_repository.id}"
  run_shell_document = "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["ab9d0263244dd0326eb67015705a667e79cfe998"]

  tags = {
    Project = "ubot"
    Purpose = "be-cicd"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# BE CD가 이미지를 ECR에 올리고 SSM으로 BE EC2에 배포함
resource "aws_iam_role" "be_cd" {
  name        = "ubot-be-cd"
  description = "GitHub Actions BE CI/CD for ${var.github_owner}/${var.be_repository.name}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = concat(
            [for branch in var.be_cd_branches : "${local.be_oidc_repository}:ref:refs/heads/${branch}"],
            ["${local.be_oidc_repository}:environment:aws-poc"],
          )
        }
      }
    }]
  })

  tags = {
    Project = "ubot"
    Purpose = "be-cicd"
  }
}

resource "aws_iam_role_policy" "be_cd" {
  name = "be-cd"
  role = aws_iam_role.be_cd.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "ecr:GetAuthorizationToken", Resource = "*" },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:DescribeImages",
        ]
        Resource = aws_ecr_repository.be.arn
      },
      {
        # AWS-RunShellScript는 임의 명령을 실행할 수 있어 대상 인스턴스를 BE EC2로 제한함
        Effect = "Allow"
        Action = "ssm:SendCommand"
        Resource = [
          local.run_shell_document,
          "arn:aws:ec2:${var.aws_region}:${local.account_id}:instance/${var.be_instance_id}",
        ]
      },
      { Effect = "Allow", Action = "ssm:GetCommandInvocation", Resource = "*" },
    ]
  })
}

# FE CD가 SSM으로 FE EC2에 커밋 빌드·릴리스를 요청함
resource "aws_iam_role" "fe_cd" {
  name        = "ubot-fe-cd"
  description = "GitHub Actions FE CD for ${var.github_owner}/${var.fe_repository.name}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = [
            for branch in var.fe_cd_branches : "${local.fe_oidc_repository}:ref:refs/heads/${branch}"
          ]
        }
      }
    }]
  })

  tags = {
    Project = "ubot"
    Purpose = "fe-cicd"
  }
}

resource "aws_iam_role_policy" "fe_cd" {
  name = "fe-cd"
  role = aws_iam_role.fe_cd.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = "ssm:SendCommand"
        Resource = [
          local.run_shell_document,
          "arn:aws:ec2:${var.aws_region}:${local.account_id}:instance/${var.fe_instance_id}",
        ]
      },
      { Effect = "Allow", Action = "ssm:GetCommandInvocation", Resource = "*" },
    ]
  })
}
