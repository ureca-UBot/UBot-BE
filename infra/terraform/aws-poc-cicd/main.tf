data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  publish_subject          = var.github_publish_subject != null ? var.github_publish_subject : "repo:${var.github_repository}:ref:refs/heads/develop"
  deploy_subject           = var.github_deploy_subject != null ? var.github_deploy_subject : "repo:${var.github_repository}:environment:aws-poc"
  github_oidc_provider_arn = var.github_oidc_provider_arn != null ? var.github_oidc_provider_arn : aws_iam_openid_connect_provider.github[0].arn
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.github_oidc_provider_arn == null ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ecr_repository" "be" {
  name                 = "${var.project_name}-be-aws-poc"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
}

data "aws_iam_policy_document" "github_publish_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [local.publish_subject]
    }
  }
}

resource "aws_iam_role" "github_publish" {
  name               = "${var.project_name}-be-aws-poc-publish"
  assume_role_policy = data.aws_iam_policy_document.github_publish_trust.json
}

data "aws_iam_policy_document" "github_publish" {
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.be.arn]
  }
}

resource "aws_iam_role_policy" "github_publish" {
  name   = "publish-be-image"
  role   = aws_iam_role.github_publish.id
  policy = data.aws_iam_policy_document.github_publish.json
}

resource "aws_ssm_document" "be_deploy" {
  name            = "${var.project_name}-be-aws-poc-deploy"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Deploy an immutable BE image to the existing UBot BE EC2"
    parameters = {
      ImageDigest = {
        type           = "String"
        description    = "ECR image digest, sha256: followed by 64 lowercase hex characters"
        allowedPattern = "^sha256:[a-f0-9]{64}$"
      }
    }
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "deployBackend"
      inputs = {
        timeoutSeconds = "1200"
        runCommand = [
          "sudo -u ssm-user bash <<'UBOT_DEPLOY'",
          "set -euo pipefail",
          "root='${var.be_checkout_path}'",
          "image='${aws_ecr_repository.be.repository_url}@{{ ImageDigest }}'",
          "digest='{{ ImageDigest }}'",
          "release_dir=\"$root/releases/$${digest#sha256:}\"",
          "test -f \"$root/.env\"",
          "mkdir -p \"$release_dir\"",
          "aws ecr get-login-password --region '${var.aws_region}' | sudo docker login --username AWS --password-stdin '${split("/", aws_ecr_repository.be.repository_url)[0]}' >/dev/null",
          "sudo docker pull \"$image\"",
          "container_id=\"$(sudo docker create \"$image\")\"",
          "trap 'sudo docker rm -f \"$container_id\" >/dev/null 2>&1 || true' EXIT",
          "sudo docker cp \"$container_id:/opt/ubot-deploy/.\" \"$release_dir/\"",
          "ln -sfn \"$root/.env\" \"$release_dir/.env\"",
          "UBOT_BE_STATE_DIR=\"$root/.deploy\" bash \"$release_dir/tools/deploy/aws-be-deploy.sh\" '${aws_ecr_repository.be.repository_url}' '{{ ImageDigest }}'",
          "UBOT_DEPLOY"
        ]
      }
    }]
  })
}

data "aws_iam_policy_document" "github_deploy_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [local.deploy_subject]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${var.project_name}-be-aws-poc-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_deploy_trust.json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    actions = ["ssm:SendCommand"]
    resources = [
      aws_ssm_document.be_deploy.arn,
      "arn:${data.aws_partition.current.partition}:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:instance/${var.be_instance_id}",
    ]
  }
  statement {
    actions   = ["ssm:GetCommandInvocation"]
    resources = ["*"]
  }
  statement {
    actions   = ["ecr:DescribeImages"]
    resources = [aws_ecr_repository.be.arn]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy-be-image"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}

data "aws_iam_policy_document" "be_ecr_pull" {
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.be.arn]
  }
}

resource "aws_iam_role_policy" "be_ecr_pull" {
  name   = "pull-be-aws-poc-image"
  role   = var.be_instance_role_name
  policy = data.aws_iam_policy_document.be_ecr_pull.json
}
