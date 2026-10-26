# 채팅·임베딩용 스폿 GPU ASG. 운영 중에는 1~3대(1대 상시, 사용률에 따라 2대까지 더)
# 온디맨드는 ASG 밖(gpu_on_demand.tf)에 두고 같은 AMI로 띄움. #282 분석 동안에는 스폿만 채팅을 받음
# 인스턴스는 gpu_ami.tf로 만든 AMI에서 부팅해 vLLM을 스스로 띄움. 사용자 데이터는 GPU 사용률을 보내는
# CloudWatch 에이전트 설정만 함
data "aws_ami" "gpu" {
  owners = ["self"]

  filter {
    name   = "image-id"
    values = [var.gpu_ami_id]
  }
}

locals {
  gpu_ami_root_volume_size = tonumber(one([
    for mapping in data.aws_ami.gpu.block_device_mappings : mapping.ebs.volume_size
    if mapping.device_name == data.aws_ami.gpu.root_device_name
  ]))
}

resource "aws_launch_template" "gpu" {
  name                   = "ubot-gpu-lt"
  description            = "Minimal vLLM AMI (gpu_ami.tf) + CloudWatch agent config in user data"
  image_id               = var.gpu_ami_id
  instance_type          = "g6.xlarge"
  update_default_version = true
  user_data              = filebase64("${path.module}/files/gpu-cwagent.sh")

  iam_instance_profile {
    arn = aws_iam_instance_profile.gpu.arn
  }

  # 사설 서브넷에 NAT가 없어 SSM과 에이전트 연결을 위해 공인 IP를 붙임
  network_interfaces {
    device_index                = 0
    associate_public_ip_address = true
    delete_on_termination       = true
    security_groups             = [aws_security_group.gpu.id]
  }

  block_device_mappings {
    device_name = "/dev/sda1"
    # AMI 루트 디스크 크기를 그대로 씀. 작게 만든 AMI로 바꾸면 디스크도 같이 줄어듦
    ebs {
      volume_size           = local.gpu_ami_root_volume_size
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
      # AMI 스냅샷에서 복원한 볼륨은 블록을 지연 적재함. 초기화 속도 적용 전 모델 읽기 약 18분, 적용 후 약 4분 걸림
      volume_initialization_rate = 300
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name    = "ubot-gpu"
      Project = "ubot"
    }
  }

  tag_specifications {
    resource_type = "volume"
    tags = {
      Name    = "ubot-gpu"
      Project = "ubot"
    }
  }
}

resource "aws_autoscaling_group" "gpu" {
  name     = "ubot-gpu-asg"
  min_size = var.gpu_min_size
  max_size = var.gpu_max_size
  # g6.xlarge는 2b에 없고 2c·2d에는 인터넷이 되는 서브넷이 없어 2a 퍼블릭만 씀
  vpc_zone_identifier = [aws_subnet.main["public_2a"].id]
  # 할당량이 허용하면 스폿 중단으로 인스턴스가 멈추기 전에 대체 인스턴스를 준비함
  capacity_rebalance        = true
  health_check_type         = "ELB"
  health_check_grace_period = 1200
  # 부팅과 모델 적재에 약 5분 걸리며 이때 낮은 GPU 사용률이 축소를 유발하면 안 됨
  default_instance_warmup = 420
  # 스폿 용량 준비가 늦어도 terraform apply가 기다리지 않게 함
  wait_for_capacity_timeout = "0"
  metrics_granularity       = "1Minute"
  enabled_metrics = [
    "GroupAndWarmPoolDesiredCapacity", "GroupAndWarmPoolTotalCapacity", "GroupDesiredCapacity",
    "GroupInServiceCapacity", "GroupInServiceInstances", "GroupMaxSize", "GroupMinSize",
    "GroupPendingCapacity", "GroupPendingInstances", "GroupStandbyCapacity", "GroupStandbyInstances",
    "GroupTerminatingCapacity", "GroupTerminatingInstances", "GroupTerminatingRetainedCapacity",
    "GroupTerminatingRetainedInstances", "GroupTotalCapacity", "GroupTotalInstances",
    "WarmPoolDesiredCapacity", "WarmPoolMinSize", "WarmPoolPendingCapacity",
    "WarmPoolPendingRetainedCapacity", "WarmPoolTerminatingCapacity",
    "WarmPoolTerminatingRetainedCapacity", "WarmPoolTotalCapacity", "WarmPoolWarmedCapacity",
  ]

  mixed_instances_policy {
    instances_distribution {
      on_demand_base_capacity                  = 0
      on_demand_percentage_above_base_capacity = 0
      spot_allocation_strategy                 = "price-capacity-optimized"
    }

    launch_template {
      launch_template_specification {
        launch_template_id = aws_launch_template.gpu.id
        version            = "$Default"
      }
      override {
        instance_type = "g6.xlarge"
      }
    }
  }

  dynamic "traffic_source" {
    for_each = aws_lb_target_group.ai
    content {
      identifier = traffic_source.value.arn
      type       = "elbv2"
    }
  }
}

# 스폿들의 평균 GPU 사용률로 gpu_min_size~gpu_max_size 사이에서 늘리고 줄임(온디맨드는 지표에 없음)
resource "aws_autoscaling_policy" "gpu_utilization" {
  name                   = "gpu-utilization-60"
  autoscaling_group_name = aws_autoscaling_group.gpu.name
  policy_type            = "TargetTrackingScaling"

  target_tracking_configuration {
    target_value     = 60
    disable_scale_in = false

    customized_metric_specification {
      namespace   = "CWAgent"
      metric_name = "nvidia_smi_utilization_gpu"
      statistic   = "Average"

      metric_dimension {
        name  = "AutoScalingGroupName"
        value = aws_autoscaling_group.gpu.name
      }
    }
  }
}
