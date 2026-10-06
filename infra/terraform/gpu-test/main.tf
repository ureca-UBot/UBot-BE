# Shared network is intentionally discovered by tags so the GPU stack can be
# destroyed/recreated independently from the long-lived network stack.
data "aws_vpc" "main" {
  filter {
    name   = "tag:Name"
    values = [var.network_vpc_name]
  }
}

data "aws_subnet" "public" {
  filter {
    name   = "tag:Name"
    values = [var.network_public_subnet_name]
  }

  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.main.id]
  }
}

# Latest AWS Deep Learning Base OSS Nvidia Driver GPU AMI (Ubuntu 24.04).
# AWS documents this public SSM parameter and the AMI supports G6 instances.
data "aws_ssm_parameter" "dlami_gpu_ubuntu_2404" {
  name = "/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id"
}

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "gpu_test" {
  name               = "${var.project_name}-gpu-test-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.gpu_test.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "gpu_test" {
  name = "${var.project_name}-gpu-test-profile"
  role = aws_iam_role.gpu_test.name
}

resource "aws_security_group" "gpu_test" {
  name        = "${var.project_name}-gpu-test-sg"
  description = "Security group for UBot GPU inference test server"
  vpc_id      = data.aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-gpu-test-sg"
  }
}

# No SSH ingress: administration is done through AWS Systems Manager Session Manager.
resource "aws_vpc_security_group_ingress_rule" "llm_api" {
  for_each = toset(var.llm_allowed_cidrs)

  security_group_id = aws_security_group.gpu_test.id
  description       = "OpenAI-compatible LLM API from approved CIDR"
  cidr_ipv4         = each.value
  from_port         = var.llm_port
  to_port           = var.llm_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.gpu_test.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_instance" "gpu_test" {
  ami           = data.aws_ssm_parameter.dlami_gpu_ubuntu_2404.value
  instance_type = var.gpu_instance_type
  subnet_id     = data.aws_subnet.public.id

  associate_public_ip_address = true

  vpc_security_group_ids = [
    aws_security_group.gpu_test.id
  ]

  iam_instance_profile = aws_iam_instance_profile.gpu_test.name

  depends_on = [
    aws_iam_role_policy_attachment.ssm
  ]

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  dynamic "instance_market_options" {
    for_each = var.use_spot ? [1] : []

    content {
      market_type = "spot"

      spot_options {
        instance_interruption_behavior = "terminate"
        spot_instance_type             = "one-time"
        max_price                      = var.spot_max_price
      }
    }
  }

  root_block_device {
    volume_type = var.root_volume_type
    volume_size = var.root_volume_size
    iops        = var.root_volume_iops
    throughput  = var.root_volume_throughput

    encrypted             = true
    delete_on_termination = true
  }

  user_data                   = templatefile("${path.module}/scripts/bootstrap.sh.tftpl", {})
  user_data_replace_on_change = true

  tags = {
    Name = "${var.project_name}-gpu-test"
  }
}

resource "aws_eip" "gpu_test" {
  count  = var.create_eip ? 1 : 0
  domain = "vpc"

  tags = {
    Name = "${var.project_name}-gpu-test-eip"
  }
}

resource "aws_eip_association" "gpu_test" {
  count = var.create_eip ? 1 : 0

  instance_id   = aws_instance.gpu_test.id
  allocation_id = aws_eip.gpu_test[0].id
}
