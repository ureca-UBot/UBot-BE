# 콘솔에서 만든 보안 그룹을 규칙째 가져온 것임. 규칙을 인라인으로 두어 가져올 리소스 ID를 그룹 6개로 줄임
# 같은 그룹에 aws_vpc_security_group_*_rule 리소스를 섞으면 서로 규칙을 지우므로 섞지 않음
# 설명·이름 태그는 AWS에 있는 값 그대로임(바꾸면 설명은 그룹 교체가 일어남)

# FE EC2: 화면과 /api 프록시(Nginx, Certbot HTTPS)
resource "aws_security_group" "fe" {
  name        = "ubot-fe-sg"
  description = "fe"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["1.245.130.27/32"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "ubot-fe-sg"
  }
}

# BE-API EC2의 API·BE Nginx. 80은 FE Nginx에서만 받음
resource "aws_security_group" "be" {
  name        = "ubot-be-sg"
  description = "ubot-be-sg"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.fe.id]
  }

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["1.245.130.27/32", "1.236.168.109/32"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "ubot-be-sg"
  }
}

# ai-worker가 API 인스턴스에 함께 돌므로 BE-API에 be·worker 그룹을 모두 붙임
# 들어오는 규칙은 없고 State·AI NLB 규칙에서 출발지로만 쓰임
resource "aws_security_group" "worker" {
  name        = "ubot-worker-sg"
  description = "UBot AI worker autoscaling instances"
  vpc_id      = aws_vpc.main.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "ubot-worker-sg"
  }
}

# State EC2의 PostgreSQL·Redis는 API와 워커에서만 받음
resource "aws_security_group" "state" {
  name        = "ubot-state-sg"
  description = "UBot PostgreSQL and Redis state server"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "be-postgresql"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.be.id]
  }

  ingress {
    description     = "be-redis"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.be.id]
  }

  ingress {
    description     = "worker-postgresql"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id]
  }

  ingress {
    description     = "worker-redis"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# GPU(ASG, AMI 빌더)의 vLLM은 AI NLB를 거친 요청만 받음
resource "aws_security_group" "gpu" {
  name        = "ubot-gpu-sg"
  description = "UBot GPU inference instances"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8000
    to_port         = 8000
    protocol        = "tcp"
    security_groups = [aws_security_group.ai_nlb.id]
  }

  ingress {
    from_port       = 8001
    to_port         = 8001
    protocol        = "tcp"
    security_groups = [aws_security_group.ai_nlb.id]
  }

  ingress {
    description = "-sunji-"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["124.59.78.137/32"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# 채팅(8000)은 워커만, 임베딩(8001)은 API와 워커가 호출함
resource "aws_security_group" "ai_nlb" {
  name        = "ubot-ai-nlb-sg"
  description = "UBot internal AI load balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8000
    to_port         = 8000
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id]
  }

  ingress {
    from_port       = 8001
    to_port         = 8001
    protocol        = "tcp"
    security_groups = [aws_security_group.worker.id, aws_security_group.be.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
