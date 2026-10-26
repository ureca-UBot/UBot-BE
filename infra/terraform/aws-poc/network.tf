# 콘솔에서 만든 ubot-prod-vpc를 그대로 가져온 것임. 이름 태그도 AWS에 있는 값 그대로 둠
resource "aws_vpc" "main" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_hostnames = true

  tags = {
    Name = "ubot-prod-vpc"
  }

  lifecycle {
    prevent_destroy = true
  }
}

locals {
  # route_table이 null이면 명시 연결 없이 VPC 기본 라우팅 테이블을 씀
  subnets = {
    public_2a = {
      name        = "ubot-prod-subnet-public1-ap-northeast-2a"
      az          = "ap-northeast-2a"
      cidr        = "10.20.0.0/20"
      public_ip   = true
      route_table = "public"
    }
    # 이름은 public이지만 공인 IP 자동 할당은 꺼져 있음. FE는 EIP를 씀
    public_2b = {
      name        = "ubot-prod-subnet-public2-ap-northeast-2b"
      az          = "ap-northeast-2b"
      cidr        = "10.20.16.0/20"
      public_ip   = false
      route_table = "public"
    }
    private_2a = {
      name        = "ubot-prod-subnet-private1-ap-northeast-2a"
      az          = "ap-northeast-2a"
      cidr        = "10.20.128.0/20"
      public_ip   = false
      route_table = "private_2a"
    }
    private_2b = {
      name        = "ubot-prod-subnet-private2-ap-northeast-2b"
      az          = "ap-northeast-2b"
      cidr        = "10.20.144.0/20"
      public_ip   = false
      route_table = "private_2b"
    }
    # 지금 쓰는 리소스 없음
    private_2c = {
      name        = "ubot-prod-subnet-private3-ap-northeast-2c"
      az          = "ap-northeast-2c"
      cidr        = "10.20.160.0/20"
      public_ip   = false
      route_table = null
    }
  }

  private_route_tables = {
    private_2a = "ubot-prod-rtb-private1-ap-northeast-2a"
    private_2b = "ubot-prod-rtb-private2-ap-northeast-2b"
  }
}

resource "aws_subnet" "main" {
  for_each = local.subnets

  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.value.az
  cidr_block              = each.value.cidr
  map_public_ip_on_launch = each.value.public_ip

  tags = {
    Name = each.value.name
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "ubot-prod-igw"
  }
}

# public_2a·public_2b가 쓰는 테이블임. 이름 태그는 콘솔에서 붙인 값 그대로임
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "aws-ubot-test-1"
  }
}

# NAT가 없음. S3 게이트웨이 엔드포인트 경로는 aws_vpc_endpoint.s3가 관리함
resource "aws_route_table" "private" {
  for_each = local.private_route_tables

  vpc_id = aws_vpc.main.id

  tags = {
    Name = each.value
  }
}

# 명시 연결이 없는 private_2c가 씀. 이 테이블에도 IGW 경로가 있음
resource "aws_default_route_table" "main" {
  default_route_table_id = aws_vpc.main.default_route_table_id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
}

resource "aws_route_table_association" "main" {
  for_each = { for key, subnet in local.subnets : key => subnet if subnet.route_table != null }

  subnet_id = aws_subnet.main[each.key].id
  route_table_id = (each.value.route_table == "public"
    ? aws_route_table.public.id
    : aws_route_table.private[each.value.route_table].id
  )
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [for table in aws_route_table.private : table.id]

  tags = {
    Name = "ubot-prod-vpce-s3"
  }
}
