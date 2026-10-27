locals {
  ec2_assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# BE EC2가 배포 이미지를 ECR에서 가져옴. 기존 BE 인스턴스는 이 스택의 관리 대상이 아니므로
# 인스턴스 프로파일 연결은 이 스택 범위 밖임
resource "aws_iam_role" "be_instance" {
  name               = "ubot-be-api-instance"
  description        = "UBot BE API EC2: SSM and ECR pull"
  assume_role_policy = local.ec2_assume_role_policy

  tags = {
    Project = "ubot"
    Purpose = "be-cicd"
  }
}

resource "aws_iam_role_policy_attachment" "be_instance_ssm" {
  role       = aws_iam_role.be_instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "be_instance_ecr_pull" {
  name = "pull-be-aws-poc-image"
  role = aws_iam_role.be_instance.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "ecr:GetAuthorizationToken", Resource = "*" },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = aws_ecr_repository.be.arn
      },
    ]
  })
}

resource "aws_iam_instance_profile" "be_instance" {
  name = aws_iam_role.be_instance.name
  role = aws_iam_role.be_instance.name

  tags = {
    Project = "ubot"
    Purpose = "be-cicd"
  }
}

# GPU 인스턴스에 SSM과 스폿 확장 지표용 CloudWatch 에이전트 권한을 부여함
resource "aws_iam_role" "gpu" {
  name               = "ubot-gpu-role"
  description        = "Allows EC2 instances to call AWS services on your behalf."
  assume_role_policy = local.ec2_assume_role_policy
}

resource "aws_iam_role_policy_attachment" "gpu_ssm" {
  role       = aws_iam_role.gpu.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "gpu_cloudwatch_agent" {
  role       = aws_iam_role.gpu.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_instance_profile" "gpu" {
  name = aws_iam_role.gpu.name
  role = aws_iam_role.gpu.name
}

# 온디맨드 GPU에는 SSM과 전환기(switcher.py)가 자기 자신을 채팅·임베딩 대상 그룹에서 빼고 넣는 권한만 둠
# 스폿이 상시 1대 떠 있어 ASG는 건드리지 않음. 스폿 역할(ubot-gpu-role)에는 이 권한을 주지 않음
resource "aws_iam_role" "gpu_on_demand" {
  name               = "ubot-gpu-ondemand"
  description        = "UBot On-Demand GPU: SSM and its own NLB registration for the #282 mode switch"
  assume_role_policy = local.ec2_assume_role_policy

  tags = {
    Project = "ubot"
    Purpose = "gpu-ondemand"
  }
}

resource "aws_iam_role_policy_attachment" "gpu_on_demand_ssm" {
  role       = aws_iam_role.gpu_on_demand.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "gpu_on_demand_targets" {
  name = "switch-ai-nlb-targets"
  role = aws_iam_role.gpu_on_demand.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:RegisterTargets", "elasticloadbalancing:DeregisterTargets"]
        Resource = [for group in aws_lb_target_group.ai : group.arn]
      },
      # DescribeTargetHealth는 리소스 단위 제한을 지원하지 않음
      { Effect = "Allow", Action = "elasticloadbalancing:DescribeTargetHealth", Resource = "*" },
    ]
  })
}

resource "aws_iam_instance_profile" "gpu_on_demand" {
  name = aws_iam_role.gpu_on_demand.name
  role = aws_iam_role.gpu_on_demand.name

  tags = {
    Project = "ubot"
    Purpose = "gpu-ondemand"
  }
}

# 계정 기본 SSM 역할(DHMC). 인스턴스 역할이 없는 FE·State가 이 역할로 SSM에 연결됨(FE CD도 이 경로를 씀)
# 이 역할 자격 증명은 SSM 명령의 관리자 셸에도 쓰여 ECR 권한이 없음. BE 배포가 ssm-user로 ECR에 로그인하는 이유임
resource "aws_iam_role" "ssm_default_host" {
  name        = "AWSSystemsManagerDefaultEC2InstanceManagementRole"
  path        = "/service-role/"
  description = "AWS Systems Manager Default EC2 Instance Management Role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ssm.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm_default_host" {
  role       = aws_iam_role.ssm_default_host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedEC2InstanceDefaultPolicy"
}

# 지우면 DHMC가 꺼져 FE·State가 SSM에서 끊기고 FE CD가 실패함
# setting_id는 AWS가 돌려주는 경로 형식으로 씀(ARN으로 쓰면 terraform plan마다 변경으로 표시됨)
resource "aws_ssm_service_setting" "default_host_management" {
  setting_id    = "/ssm/managed-instance/default-ec2-instance-management-role"
  setting_value = "service-role/${aws_iam_role.ssm_default_host.name}"

  lifecycle {
    prevent_destroy = true
  }
}
