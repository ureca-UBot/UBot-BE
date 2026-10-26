variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

# 이 조직의 GitHub OIDC 주체 문자열에는 변경되지 않는 소유자와 저장소 ID가 들어감
# 형식은 repo:<owner>@<owner id>/<repo>@<repo id>:... 임
variable "github_owner" {
  type    = string
  default = "ureca-UBot"
}

variable "github_owner_id" {
  type    = string
  default = "326730402"
}

variable "be_repository" {
  type = object({ name = string, id = string })
  default = {
    name = "UBot-BE"
    id   = "1362033731"
  }
}

variable "fe_repository" {
  type = object({ name = string, id = string })
  default = {
    name = "UBot-FE"
    id   = "1362033936"
  }
}

variable "be_cd_branches" {
  description = "UBot-BE branches allowed to publish images. workflow_run jobs run on the default branch."
  type        = list(string)
  default     = ["develop"]
}

variable "fe_cd_branches" {
  description = "UBot-FE branches allowed to deploy."
  type        = list(string)
  default     = ["develop"]
}

variable "be_instance_id" {
  description = "BE EC2 (API, Worker, BE Nginx, monitoring). The BE CD role may send SSM commands only to it."
  type        = string
}

variable "fe_instance_id" {
  description = "FE EC2. The FE CD role may send SSM commands only to it."
  type        = string
}

variable "gpu_ami_id" {
  description = "GPU AMI with the NVIDIA driver, Docker, vLLM image and model cache; vLLM starts at boot."
  type        = string
}

# 스폿 ASG 대수. 운영 중에는 1/3(1대 상시 + 사용률에 따라 2대까지, 늘어나는 데 약 5분), 꺼 둘 때는 0/0
# #282 분석으로 온디맨드가 빠지는 동안 채팅을 받으므로 운영 중 최소 1대는 꼭 둠
# 스폿 G vCPU 한도(L-3819A6DF)가 12 = g6.xlarge 3대라, 3대일 때는 회수 예고를 받아도 미리 교체하지 못함
variable "gpu_min_size" {
  description = "Minimum Spot GPU instances in ubot-gpu-asg."
  type        = number

  validation {
    condition     = var.gpu_min_size >= 0 && var.gpu_min_size <= var.gpu_max_size
    error_message = "gpu_min_size must be between 0 and gpu_max_size."
  }
}

variable "gpu_max_size" {
  description = "Maximum Spot GPU instances in ubot-gpu-asg."
  type        = number

  validation {
    condition     = var.gpu_max_size >= 0 && var.gpu_max_size <= 3
    error_message = "gpu_max_size must be between 0 and 3 (Spot G quota is 12 vCPU)."
  }
}

# 온디맨드 G vCPU 한도(L-DB2E81BA)가 8 = g6.xlarge 2대라 온디맨드 GPU와 AMI 빌더가 함께 쓸 수 있음
variable "gpu_on_demand_enabled" {
  description = "Create the On-Demand GPU (chat/embedding, administrator 14B model during #282 analysis)."
  type        = bool
  default     = false
}

# 스폿 AMI 디스크 + Qwen3-14B-AWQ 체크포인트 약 10GB + 여유
variable "gpu_on_demand_volume_size" {
  description = "Root volume of the On-Demand GPU in GiB; must be at least the GPU AMI root volume."
  type        = number
  default     = 70
}

variable "gpu_builder_enabled" {
  description = "Create the GPU AMI builder (gpu_ami.tf). Turn off after the AMI is made."
  type        = bool
  default     = false
}

# vLLM 이미지 약 23GB(압축 9GB) + 모델 약 5GB + OS·드라이버를 담고 이미지 내려받기 여유를 둔 값
variable "gpu_ami_volume_size" {
  description = "Root volume of the AMI builder in GiB; it becomes the minimum volume size of the new AMI."
  type        = number
  default     = 50
}
