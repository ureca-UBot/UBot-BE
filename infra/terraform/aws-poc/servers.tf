# 콘솔에서 만든 FE·BE-API·State 서버를 그대로 가져온 것임. GPU는 gpu.tf의 ASG가 AMI로 띄움
# 교체되면 루트 디스크(DB, 서버 .env, FE 릴리스)가 사라지므로 삭제를 막음
# 사설 IP는 고정함. FE Nginx의 /api 프록시, BE .env의 FE_PRIVATE_IP·DB·Redis 주소가 이 값을 씀
# FE·State는 인스턴스 역할 없이 계정 기본 SSM 역할(DHMC, iam_instances.tf)로 SSM에 연결됨

locals {
  # Ubuntu 24.04. FE만 Ubuntu Pro 이미지로 띄웠음
  ubuntu_ami     = "ami-0e4ab31f1847c850c"
  ubuntu_pro_ami = "ami-0261371e79ad278c1"
}
# 화면 제공과 /api 프록시(Nginx). CD는 SSM으로 이 서버에서 직접 빌드함
resource "aws_instance" "fe" {
  ami                    = local.ubuntu_pro_ami
  instance_type          = "t3.small"
  subnet_id              = aws_subnet.main["public_2b"].id
  private_ip             = "10.20.22.42"
  vpc_security_group_ids = [aws_security_group.fe.id]
  key_name               = "ubot-front"

  credit_specification {
    cpu_credits = "unlimited"
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size = 10
    volume_type = "gp3"
  }

  tags = {
    Name = "ubot-fe"
  }

  lifecycle {
    prevent_destroy = true
  }
}
# u-bot.cloud DNS가 이 주소를 가리킴
resource "aws_eip" "fe" {
  domain   = "vpc"
  instance = aws_instance.fe.id

  lifecycle {
    prevent_destroy = true
  }
}

# API, ai-worker(APP_ROLE=worker), BE Nginx, 모니터링을 함께 실행함
resource "aws_instance" "be_api" {
  ami = local.ubuntu_ami
  # 3,000명 부하 시험 동안만 t3.xlarge(4 vCPU). 시험 뒤 t3.large로 되돌릴지 결정함
  instance_type          = "t3.xlarge"
  subnet_id              = aws_subnet.main["public_2b"].id
  private_ip             = "10.20.19.183"
  vpc_security_group_ids = [aws_security_group.worker.id, aws_security_group.be.id]
  key_name               = "ubot-be"
  iam_instance_profile   = aws_iam_instance_profile.be_instance.name

  credit_specification {
    cpu_credits = "unlimited"
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size = 50
    volume_type = "gp3"
  }

  tags = {
    Name = "ubot-be-API"
  }

  lifecycle {
    prevent_destroy = true
  }
}

# PostgreSQL·Redis. BE CD는 이 서버를 건드리지 않음
resource "aws_instance" "state" {
  ami                    = local.ubuntu_ami
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.main["public_2a"].id
  private_ip             = "10.20.13.254"
  vpc_security_group_ids = [aws_security_group.state.id]
  key_name               = "ubot-state"

  credit_specification {
    cpu_credits = "unlimited"
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size = 100
    volume_type = "gp3"
  }

  tags = {
    Name = "ubot-State(DB+Redis)"
  }

  lifecycle {
    prevent_destroy = true
  }
}
