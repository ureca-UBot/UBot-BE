# GPU AMI 빌더. gpu_builder_enabled = true일 때만 만들고, AMI를 만든 뒤에는 false로 돌려 지움
# 순서: 빌더 생성 → 첫 부팅 setup.sh(설치 후 재부팅) → vLLM 기동·모델 내려받기
#   → NLB로 BE 연결 확인 → finalize.sh → create-image → gpu_ami_id 교체 → 빌더 제거
# 기존 AMI는 ubot-gpu-1을 통째로 복제해 디스크 100GB(실데이터 47.7GB)였음. 필요한 것만 담아 줄임

data "aws_ssm_parameter" "ubuntu_noble" {
  count = var.gpu_builder_enabled ? 1 : 0

  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
}

locals {
  # 서비스 정의는 BE 저장소의 infra/llm 구성을 그대로 쓰고, 값은 AWS L4용 vllm.env를 씀
  gpu_ami_files = {
    "/opt/ubot-gpu/docker-compose.yml"      = { source = "${path.module}/../../llm/docker-compose.yml", permissions = "0644" }
    "/opt/ubot-gpu/.env"                    = { source = "${path.module}/files/gpu-ami/vllm.env", permissions = "0600" }
    "/opt/ubot-gpu/compose.offline.yaml"    = { source = "${path.module}/files/gpu-ami/compose.offline.yaml", permissions = "0644" }
    "/opt/ubot-gpu/setup.sh"                = { source = "${path.module}/files/gpu-ami/setup.sh", permissions = "0755" }
    "/opt/ubot-gpu/finalize.sh"             = { source = "${path.module}/files/gpu-ami/finalize.sh", permissions = "0755" }
    "/etc/systemd/system/ubot-vllm.service" = { source = "${path.module}/files/gpu-ami/ubot-vllm.service", permissions = "0644" }
  }

  gpu_ami_user_data = join("\n", ["#cloud-config", yamlencode({
    write_files = [for path, f in local.gpu_ami_files : {
      path        = path
      permissions = f.permissions
      # Windows 체크아웃(core.autocrlf)의 CRLF를 LF로 바꿈. CR이 남으면 셸 스크립트와 .env 값이 깨짐
      content = replace(file(f.source), "\r\n", "\n")
    }]
    runcmd = [["bash", "/opt/ubot-gpu/setup.sh"]]
  })])
}

resource "aws_instance" "gpu_builder" {
  count = var.gpu_builder_enabled ? 1 : 0

  ami                    = data.aws_ssm_parameter.ubuntu_noble[0].insecure_value
  instance_type          = "g6.xlarge"
  subnet_id              = aws_subnet.main["public_2a"].id
  vpc_security_group_ids = [aws_security_group.gpu.id]
  iam_instance_profile   = aws_iam_instance_profile.gpu.name
  # 키를 두지 않음. 접속은 SSM으로만 하며 AMI에 키가 남지 않음
  user_data_base64 = base64gzip(local.gpu_ami_user_data)

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  # 이 크기가 새 AMI의 최소 디스크 크기가 됨(EBS는 줄일 수 없음)
  root_block_device {
    volume_size = var.gpu_ami_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name    = "ubot-gpu-ami-builder"
    Project = "ubot"
  }

  # Ubuntu 새 릴리스가 나와도 빌드 중인 서버를 바꾸지 않음
  lifecycle {
    ignore_changes = [ami]
  }
}

# BE가 실제 경로(AI NLB)로 빌더를 호출하게 함. ASG가 0대일 때 확인하면 빌더만 요청을 받음
resource "aws_lb_target_group_attachment" "gpu_builder" {
  for_each = var.gpu_builder_enabled ? local.ai_target_groups : {}

  target_group_arn = aws_lb_target_group.ai[each.key].arn
  target_id        = aws_instance.gpu_builder[0].id
  port             = each.value.port
}
