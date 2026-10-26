# 온디맨드 GPU. 평소에는 스폿과 함께 NLB로 채팅(8000)·임베딩(8001)을 받고,
# #282 관리자 분석 동안만 NLB에서 빠져 Qwen3-14B-AWQ 전용으로 돌아감(L4 한 장에 둘을 함께 못 띄움)
# 전환은 서버의 switcher.py가 controller.py 앞 프록시로 함. 이 서버의 NLB 등록은 Terraform이 관리하지 않음
# (관리하면 분석 중 apply가 서버를 NLB에 다시 붙여 14B가 도는 서버로 채팅이 들어감)
# AMI는 스폿용(gpu_ami_id)을 그대로 쓰고 #282 구성 요소는 첫 부팅에 한 번 설치함
# 켜기 전에 필요한 것: 새 스폿 AMI, 이 브랜치에 PR #282(controller.py·compose.example.yaml) 반영

locals {
  guard_review_runtime_dir = "${path.module}/../../guard-review-runtime"
  # BE .env의 GUARD_REVIEW_LLM_BASE_URL(:18175/v1)·GUARD_REVIEW_RUNTIME_BASE_URL(:18176)이 이 주소를 씀
  gpu_on_demand_private_ip = "10.20.12.50"

  gpu_on_demand_sources = {
    controller = "${local.guard_review_runtime_dir}/controller.py"
    compose    = "${local.guard_review_runtime_dir}/compose.example.yaml"
  }
  gpu_on_demand_ready = alltrue([for source in values(local.gpu_on_demand_sources) : fileexists(source)])

  # PR #282가 들어오기 전에는 파일이 없으므로 내용 대신 빈 값을 두고 사전 조건에서 막음
  gpu_on_demand_files = {
    "/opt/ubot-admin-risk/controller.py" = {
      permissions = "0644"
      content     = fileexists(local.gpu_on_demand_sources.controller) ? file(local.gpu_on_demand_sources.controller) : ""
    }
    "/opt/ubot-admin-risk/compose.yaml" = {
      permissions = "0644"
      content     = fileexists(local.gpu_on_demand_sources.compose) ? file(local.gpu_on_demand_sources.compose) : ""
    }
    "/opt/ubot-admin-risk/switcher.py" = {
      permissions = "0644"
      content     = file("${local.guard_review_runtime_dir}/switcher.py")
    }
    "/opt/ubot-admin-risk/setup.sh" = {
      permissions = "0755"
      content     = file("${path.module}/files/gpu-ondemand/setup.sh")
    }
    "/etc/systemd/system/ubot-admin-risk-controller.service" = {
      permissions = "0644"
      content     = file("${path.module}/files/gpu-ondemand/ubot-admin-risk-controller.service")
    }
    "/etc/systemd/system/ubot-gpu-mode-switcher.service" = {
      permissions = "0644"
      content     = file("${path.module}/files/gpu-ondemand/ubot-gpu-mode-switcher.service")
    }
    # 토큰이 아닌 설정만 둠. 토큰(runtime.env)은 서버에 손으로 넣음
    "/etc/ubot-admin-risk/switcher.env" = {
      permissions = "0644"
      content = join("\n", [
        "AWS_REGION=${var.aws_region}",
        "SWITCHER_CHAT_TARGET_GROUP_ARN=${aws_lb_target_group.ai["chat"].arn}",
        "SWITCHER_EMBEDDING_TARGET_GROUP_ARN=${aws_lb_target_group.ai["embedding"].arn}",
        "",
      ])
    }
  }

  gpu_on_demand_user_data = join("\n", ["#cloud-config", yamlencode({
    write_files = [for path, f in local.gpu_on_demand_files : {
      path        = path
      permissions = f.permissions
      # Windows 체크아웃(core.autocrlf)의 CRLF를 LF로 바꿈
      content = replace(f.content, "\r\n", "\n")
    }]
    runcmd = [["bash", "/opt/ubot-admin-risk/setup.sh"]]
  })])
}

# BE 워커만 전환기(18176)와 관리자 모델(18175)에 닿게 함. 18175는 인증이 없어 이 규칙으로만 막음
resource "aws_security_group" "gpu_admin" {
  name        = "ubot-gpu-admin-sg"
  description = "UBot On-Demand GPU administrator model and runtime control from the AI worker"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "admin-model-and-control"
    from_port       = 18175
    to_port         = 18176
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name    = "ubot-gpu-admin-sg"
    Project = "ubot"
  }
}

resource "aws_instance" "gpu_on_demand" {
  count = var.gpu_on_demand_enabled ? 1 : 0

  ami           = var.gpu_ami_id
  instance_type = "g6.xlarge"
  subnet_id     = aws_subnet.main["public_2a"].id
  private_ip    = local.gpu_on_demand_private_ip
  # gpu는 NLB에서 오는 8000·8001, gpu_admin은 워커에서 오는 18175·18176을 허용함
  vpc_security_group_ids = [aws_security_group.gpu.id, aws_security_group.gpu_admin.id]
  iam_instance_profile   = aws_iam_instance_profile.gpu_on_demand.name
  key_name               = "ubot-llm"
  user_data_base64       = base64gzip(local.gpu_on_demand_user_data)

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  # aws_instance는 디스크 초기화 속도를 지원하지 않아 첫 부팅의 모델 로딩만 느림(이후 부팅은 영향 없음)
  root_block_device {
    volume_size = var.gpu_on_demand_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name    = "ubot-gpu-ondemand"
    Project = "ubot"
  }

  # 14B 체크포인트(약 10GB)를 다시 받지 않게 AMI·설치 파일이 바뀌어도 서버를 교체하지 않음
  # 새 AMI로 바꾸려면 terraform apply -replace=aws_instance.gpu_on_demand[0]
  lifecycle {
    ignore_changes = [ami, user_data_base64]

    precondition {
      condition     = local.gpu_on_demand_ready
      error_message = "PR #282의 infra/guard-review-runtime/controller.py와 compose.example.yaml을 이 브랜치에 반영한 뒤 켬"
    }

    precondition {
      condition     = var.gpu_on_demand_volume_size >= local.gpu_ami_root_volume_size + 15
      error_message = "gpu_on_demand_volume_size는 GPU AMI 디스크보다 15GiB 이상 커야 함(14B 체크포인트와 여유)"
    }
  }
}
