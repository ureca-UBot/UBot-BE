# GPU vLLM 앞의 내부 NLB임. 채팅은 8000, 임베딩은 8001
# GPU는 ASG가 등록하고, AMI를 만드는 동안에는 빌더를 gpu_ami.tf에서 등록함
resource "aws_lb" "ai" {
  name                             = "ubot-ai-nlb"
  internal                         = true
  load_balancer_type               = "network"
  subnets                          = [aws_subnet.main["private_2a"].id, aws_subnet.main["private_2b"].id]
  security_groups                  = [aws_security_group.ai_nlb.id]
  enable_cross_zone_load_balancing = false
  dns_record_client_routing_policy = "any_availability_zone"
}

locals {
  ai_target_groups = {
    chat      = { name = "ubot-ai-chat-tg", port = 8000 }
    embedding = { name = "ubot-ai-embedding-tg", port = 8001 }
  }
}

resource "aws_lb_target_group" "ai" {
  for_each = local.ai_target_groups

  name        = each.value.name
  port        = each.value.port
  protocol    = "TCP"
  target_type = "instance"
  vpc_id      = aws_vpc.main.id
  # BE LLM 읽기 제한 시간과 같게 두어 대상 제외 중 처리 중인 답변이 끝날 수 있게 함
  deregistration_delay = 120

  # vLLM은 모델 적재 후 /health에 응답함. 10초 간격 3회 성공 시 신규 GPU가 약 30초 후 서비스 상태가 됨
  health_check {
    protocol            = "HTTP"
    path                = "/health"
    port                = "traffic-port"
    matcher             = "200-399"
    interval            = 10
    timeout             = 6
    healthy_threshold   = 3
    unhealthy_threshold = 2
  }
}

resource "aws_lb_listener" "ai" {
  for_each = local.ai_target_groups

  load_balancer_arn = aws_lb.ai.arn
  port              = each.value.port
  protocol          = "TCP"

  # 리스너가 상세 전달 형식으로 생성되어 AWS 상태와 맞추기 위해 같은 형식을 유지함
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.ai[each.key].arn

    forward {
      target_group {
        arn    = aws_lb_target_group.ai[each.key].arn
        weight = 1
      }
    }
  }
}
