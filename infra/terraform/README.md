# UBot Terraform Infrastructure

UBot 테스트 인프라를 Terraform으로 생성·관리하기 위한 구성입니다.

현재 범위는 AWS 네트워크와 GPU 테스트 서버까지이며, GPU 서버에서는 vLLM/SGLang 등의 LLM Serving Runtime을 별도로 실행합니다.

Terraform CLI와 AWS CLI는 로컬에 직접 설치하지 않고 Docker를 통해 실행합니다.

---

## 구조

```text
infra/terraform/
├─ README.md
│
├─ bootstrap/
│  ├─ .terraform.lock.hcl
│  ├─ main.tf
│  ├─ outputs.tf
│  ├─ terraform.tfvars.example
│  ├─ variables.tf
│  └─ versions.tf
│
├─ network/
│  ├─ .terraform.lock.hcl
│  ├─ backend.hcl.example
│  ├─ main.tf
│  ├─ outputs.tf
│  ├─ terraform.tfvars.example
│  ├─ variables.tf
│  └─ versions.tf
│
└─ gpu-test/
   ├─ .terraform.lock.hcl
   ├─ backend.hcl.example
   ├─ main.tf
   ├─ outputs.tf
   ├─ terraform.tfvars.example
   ├─ variables.tf
   ├─ versions.tf
   └─ scripts/
      └─ bootstrap.sh.tftpl
```

Terraform 실행 도구는 인프라 코드와 분리되어 있습니다.

```text
tools/terraform/
├─ Dockerfile   → Terraform + AWS CLI 실행 이미지
└─ tf.ps1       → 실행 Wrapper
```

역할은 다음과 같습니다.

```text
bootstrap  → Terraform remote state용 S3 생성 (거의 1회만 실행)
network    → VPC / Public Subnet / IGW / Route Table 생성 (유지)
gpu-test   → Security Group / IAM+SSM / g6.xlarge GPU EC2 / optional EIP (수시 생성·삭제)
```

`gpu-test`는 `network`의 VPC/Subnet을 remote state가 아니라 Name 태그(`ubot-vpc`, `ubot-public-subnet`)로 조회합니다. `network`의 `project_name`을 바꾸면 `gpu-test`의 `network_vpc_name`, `network_public_subnet_name`도 함께 바꿔야 합니다.

---

# 사전 준비

## Docker

Terraform과 AWS CLI를 Docker로 실행하므로 Docker Desktop이 필요합니다.

확인:

```powershell
docker --version
```

---

## AWS CLI Docker Image 준비

AWS CLI도 로컬에 직접 설치하지 않고 공식 Docker 이미지를 사용합니다.

처음 한 번 이미지를 내려받습니다.

```powershell
docker pull amazon/aws-cli:latest
```

정상적으로 실행되는지 확인합니다.

```powershell
docker run --rm `
  amazon/aws-cli:latest `
  --version
```

`aws-cli/2.x.x` 형태의 버전이 출력되면 정상입니다.

AWS 로그인 정보와 Profile을 저장할 로컬 디렉터리를 생성합니다.

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.aws"
```

이 디렉터리는 AWS CLI 컨테이너와 Terraform 컨테이너가 공통으로 사용합니다.

```text
Windows
%USERPROFILE%\.aws
        │
        ├─ config
        └─ login/cache
             │
             ├─ AWS CLI Container
             └─ Terraform Container
```

장기 Access Key와 Secret Key를 직접 생성하거나 Repository에 저장하지 않습니다. `aws login`으로 발급되는 임시 Credential을 사용합니다.

---

## Terraform 실행 Image 빌드

`tf.ps1`은 Terraform과 AWS CLI가 함께 들어 있는 로컬 이미지 `ubot-terraform:1.16.4`를 사용합니다. Registry에 올라가 있지 않으므로 처음 한 번 직접 빌드합니다.

Repository Root에서:

```powershell
docker build -t ubot-terraform:1.16.4 .\tools\terraform
```

빌드한 뒤 Terraform과 AWS CLI가 모두 포함되었는지 확인합니다.

Terraform:

```powershell
docker run --rm `
  --entrypoint terraform `
  ubot-terraform:1.16.4 `
  version
```

AWS CLI:

```powershell
docker run --rm `
  --entrypoint aws `
  ubot-terraform:1.16.4 `
  --version
```

현재 검증한 Terraform 버전은 `1.16.4`입니다.

이미지 안에 AWS CLI가 필요한 이유는 아래 `ureca-terraform` Profile의 `credential_process`가 컨테이너 안에서 `aws` 명령을 실행하기 때문입니다.

---

## AWS Profile 설정

Profile 두 개를 사용하며 Region은 `ap-northeast-2`입니다.

```text
ureca
→ aws login으로 로그인하는 Profile

ureca-terraform
→ Terraform이 사용하는 Profile
→ credential_process로 ureca 로그인 세션의 임시 Credential을 가져옴
```

먼저 `ureca` Profile로 로그인합니다.

```powershell
docker run --rm -it `
  -v "$env:USERPROFILE\.aws:/root/.aws" `
  amazon/aws-cli:latest `
  login `
  --profile ureca `
  --region ap-northeast-2 `
  --remote
```

로그인하면 `%USERPROFILE%\.aws\config`에 `ureca` Profile이 생성됩니다.

예:

```ini
[profile ureca]
login_session = arn:aws:iam::<AWS_ACCOUNT_ID>:user/ureca
region = ap-northeast-2
```

그 아래에 Terraform 전용 Profile을 추가합니다.

```ini
[profile ureca-terraform]
credential_process = aws configure export-credentials --profile ureca --format process
region = ap-northeast-2
```

`tf.ps1`은 `%USERPROFILE%\.aws`를 Terraform 컨테이너에 마운트하고 다음 환경변수로 실행합니다.

```text
AWS_PROFILE=ureca-terraform
AWS_REGION=ap-northeast-2
AWS_DEFAULT_REGION=ap-northeast-2
```

Access Key/Secret/SessionToken을 `tf.ps1`에서 한 번 추출해 고정하지 않습니다.

Terraform이 Credential을 요청할 때 `credential_process`가 컨테이너 내부 AWS CLI를 통해 현재 `ureca` 로그인 세션에서 가져옵니다.

---

## AWS 로그인 확인

`ureca` Profile 확인:

```powershell
docker run --rm `
  -v "$env:USERPROFILE\.aws:/root/.aws" `
  amazon/aws-cli:latest `
  sts get-caller-identity `
  --profile ureca `
  --region ap-northeast-2
```

`ureca-terraform` Profile 확인:

```powershell
docker run --rm `
  -v "$env:USERPROFILE\.aws:/root/.aws" `
  --entrypoint aws `
  ubot-terraform:1.16.4 `
  sts get-caller-identity `
  --profile ureca-terraform `
  --region ap-northeast-2
```

`Account`, `Arn` 등이 정상적으로 출력되면 사용할 수 있습니다.

`aws login` 세션 자체가 만료된 경우에는 다시 로그인합니다.

---

## GPU vCPU 할당량 증가

신규 AWS 계정은 G 계열 인스턴스의 vCPU 할당량이 0인 경우가 많습니다.

할당량이 부족하면 `gpu-test apply`가 EC2 생성 단계에서 실패하므로 GPU 서버를 만들기 전에 AWS Console에서 할당량 증가를 요청합니다.

할당량은 Region별·구매 방식별로 따로 관리됩니다.

| 구매 방식 | Quota 이름 | Quota Code |
|---|---|---|
| Spot (기본) | All G and VT Spot Instance Requests | `L-3819A6DF` |
| On-Demand | Running On-Demand G and VT instances | `L-DB2E81BA` |

단위는 인스턴스 개수가 아니라 vCPU 수입니다.

```text
g6.xlarge   → 4 vCPU
g6.2xlarge  → 8 vCPU
```

`g6.xlarge` 1대만 띄우려면 최소 4, `g6.2xlarge`까지 테스트하려면 8 이상을 요청합니다. Spot이 안 잡힐 때 On-Demand로 전환할 수 있도록 두 항목을 함께 올려 두기를 권장합니다.

요청 방법:

```text
AWS Console (Region: ap-northeast-2 확인)
→ Service Quotas
→ AWS services
→ Amazon Elastic Compute Cloud (Amazon EC2)
→ 위 Quota 이름 검색
→ Request increase at account level
→ 필요한 vCPU 수 입력 후 Request
```

현재 Spot 할당량 확인:

```powershell
docker run --rm `
  -v "$env:USERPROFILE\.aws:/root/.aws" `
  amazon/aws-cli:latest `
  service-quotas get-service-quota `
  --service-code ec2 `
  --quota-code L-3819A6DF `
  --profile ureca `
  --region ap-northeast-2 `
  --query "Quota.Value"
```

---

# Terraform 실행

Repository Root에서 실행합니다.

```powershell
.\tools\terraform\tf.ps1 <target> <command> [추가 인자...]
```

예:

```powershell
.\tools\terraform\tf.ps1 gpu-test plan
.\tools\terraform\tf.ps1 gpu-test state list
```

`-`로 시작하는 Terraform 옵션은 PowerShell이 스크립트 파라미터로 해석할 수 있으므로 `-ExtraArgs`로 넘깁니다.

```powershell
.\tools\terraform\tf.ps1 `
  -Target gpu-test `
  -Command apply `
  -ExtraArgs "-var=use_spot=false"
```

일반적인 실행 순서:

```powershell
.\tools\terraform\tf.ps1 gpu-test fmt
.\tools\terraform\tf.ps1 gpu-test validate
.\tools\terraform\tf.ps1 gpu-test plan
.\tools\terraform\tf.ps1 gpu-test apply
```

삭제:

```powershell
.\tools\terraform\tf.ps1 gpu-test destroy
```

`apply` 또는 `destroy` 전에 `plan` 결과를 먼저 확인하기를 권장합니다.

---

## Terraform 버전

Terraform 버전은 `tools/terraform/Dockerfile`의 `TERRAFORM_VERSION`으로 고정합니다.

현재:

```text
1.16.4
```

버전을 올릴 때는 다음 세 곳을 함께 바꾸고 이미지를 다시 빌드합니다.

```text
tools/terraform/Dockerfile
→ ARG TERRAFORM_VERSION

tools/terraform/tf.ps1
→ 기본 이미지 태그 및 빌드 안내 문구

infra/terraform/README.md
→ 빌드 명령의 태그
```

다른 이미지를 임시로 쓰려면 `TF_DOCKER_IMAGE` 환경 변수로 덮어쓸 수 있습니다.

```powershell
$env:TF_DOCKER_IMAGE = "ubot-terraform:<VERSION>"
```

`credential_process`를 사용하므로 AWS CLI가 포함된 이미지여야 합니다.

S3 Lockfile(`use_lockfile`)을 사용하므로 Terraform 1.10 이상이 필요합니다.

---

# 변수 변경

기본값으로 충분하면 `terraform.tfvars` 없이 실행할 수 있습니다. 값을 바꿀 때만 각 Root에서 example을 복사해 수정합니다.

```powershell
Copy-Item `
  .\infra\terraform\gpu-test\terraform.tfvars.example `
  .\infra\terraform\gpu-test\terraform.tfvars
```

`terraform.tfvars`는 Git에 포함하지 않습니다.

---

# 1. Bootstrap

`bootstrap`은 Terraform State를 저장할 S3 Bucket을 생성합니다.

Bootstrap 자체는 S3 Bucket이 존재하기 전에 실행되어야 하므로 Local State를 사용합니다.

```powershell
.\tools\terraform\tf.ps1 bootstrap init
.\tools\terraform\tf.ps1 bootstrap validate
.\tools\terraform\tf.ps1 bootstrap plan
.\tools\terraform\tf.ps1 bootstrap apply
.\tools\terraform\tf.ps1 bootstrap output
```

생성되는 State Bucket 예:

```text
ubot-tfstate-<AWS_ACCOUNT_ID>-ap-northeast-2
```

Bucket 설정:

```text
S3 Server Side Encryption (AES256)
S3 Versioning
Public Access Block
prevent_destroy
```

Bootstrap의 Local State(`bootstrap/terraform.tfstate`)는 Git에 올라가지 않고 실행한 PC에만 남습니다.

이미 Bucket이 만들어진 계정에서는 다른 팀원이 `bootstrap apply`를 다시 실행하지 않습니다.

---

# 2. Network

Network는 Bootstrap에서 생성한 S3를 Remote Backend로 사용합니다.

```powershell
Copy-Item `
  .\infra\terraform\network\backend.hcl.example `
  .\infra\terraform\network\backend.hcl
```

`backend.hcl`의 `bucket`을 Bootstrap output의 `state_bucket_name`으로 변경합니다.

```hcl
bucket       = "ubot-tfstate-<AWS_ACCOUNT_ID>-ap-northeast-2"
key          = "ubot/network/terraform.tfstate"
region       = "ap-northeast-2"
encrypt      = true
use_lockfile = true
```

초기화:

```powershell
.\tools\terraform\tf.ps1 `
  -Target network `
  -Command init `
  -ExtraArgs "-backend-config=backend.hcl"
```

이후:

```powershell
.\tools\terraform\tf.ps1 network validate
.\tools\terraform\tf.ps1 network plan
.\tools\terraform\tf.ps1 network apply
.\tools\terraform\tf.ps1 network output
```

현재 생성 리소스:

```text
VPC
└─ 10.20.0.0/16

Public Subnet
└─ 10.20.16.0/20

Internet Gateway

Public Route Table
└─ 0.0.0.0/0 → Internet Gateway

Route Table Association
```

기본 Availability Zone:

```text
ap-northeast-2a
```

해당 AZ에 G6 용량이 없으면 `availability_zone` 변수를 변경합니다. Subnet이 재생성되므로 GPU 서버를 먼저 삭제한 뒤 적용합니다.

---

# 3. GPU Test Server

GPU Test Server는 Network Stack에서 생성한 VPC/Subnet을 사용합니다.

```powershell
Copy-Item `
  .\infra\terraform\gpu-test\backend.hcl.example `
  .\infra\terraform\gpu-test\backend.hcl
```

`backend.hcl`의 `bucket`을 Bootstrap output의 `state_bucket_name`으로 변경합니다.

```hcl
bucket       = "ubot-tfstate-<AWS_ACCOUNT_ID>-ap-northeast-2"
key          = "ubot/gpu-test/terraform.tfstate"
region       = "ap-northeast-2"
encrypt      = true
use_lockfile = true
```

초기화:

```powershell
.\tools\terraform\tf.ps1 `
  -Target gpu-test `
  -Command init `
  -ExtraArgs "-backend-config=backend.hcl"
```

이후:

```powershell
.\tools\terraform\tf.ps1 gpu-test validate
.\tools\terraform\tf.ps1 gpu-test plan
.\tools\terraform\tf.ps1 gpu-test apply
.\tools\terraform\tf.ps1 gpu-test output
```

기본 설정:

```text
Instance Type
→ g6.xlarge

GPU
→ NVIDIA L4

Purchase Option
→ Spot

OS
→ Ubuntu 24.04 AWS Deep Learning Base AMI

Root Volume
→ gp3 100GB
→ Encryption Enabled

Metadata
→ IMDSv2 필수

Connection
→ AWS Systems Manager Session Manager

SSH
→ 사용하지 않음

Elastic IP
→ 기본 미사용
→ create_eip = true 로 활성화
```

다른 인스턴스 크기가 필요하면:

```powershell
.\tools\terraform\tf.ps1 `
  -Target gpu-test `
  -Command apply `
  -ExtraArgs "-var=gpu_instance_type=g6.2xlarge"
```

---

# GPU AMI

AWS Public SSM Parameter의 최신 Deep Learning Base OSS NVIDIA Driver GPU AMI(Ubuntu 24.04)를 사용합니다.

```text
/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id
```

`latest`를 조회하므로 AWS가 AMI를 갱신하면 다음 `plan`에서 인스턴스 교체(replace)가 표시될 수 있습니다. 항상 `apply` 전에 `plan` 결과를 확인합니다.

---

# GPU Server Bootstrap

EC2의 `user_data`는 다음 템플릿을 사용합니다.

```text
gpu-test/scripts/bootstrap.sh.tftpl
```

Bootstrap 작업:

```text
기본 패키지 설치
Docker 실행
NVIDIA Container Runtime 설정
SSM Agent 실행
ubuntu 사용자를 docker group에 추가
/opt/ubot
/opt/ubot/llm
/opt/ubot/benchmarks
디렉터리 생성
GPU 상태 확인
Docker 상태 확인
Bootstrap 완료 Marker 생성
```

AWS DLAMI에는 NVIDIA Driver, CUDA, NVIDIA Container Toolkit, Docker 관련 구성이 포함되어 있으므로 Ubuntu의 `docker.io`를 다시 설치하지 않습니다. `docker.io`를 추가 설치하면 DLAMI의 `containerd.io`와 충돌할 수 있습니다.

첫 부팅 중 apt/dpkg lock 충돌을 줄이기 위해 패키지를 설치할 때 최대 300초 동안 lock을 기다립니다.

`user_data`를 수정하면 인스턴스가 교체됩니다.

```text
user_data_replace_on_change = true
```

확인:

```bash
sudo cloud-init status --wait
```

정상:

```text
status: done
```

완료 Marker:

```bash
ls -l /opt/ubot/.bootstrap-complete
```

로그:

```bash
sudo tail -n 100 /var/log/ubot-bootstrap.log
```

정상 완료 시:

```text
UBot GPU bootstrap completed successfully.
```

---

# GPU 확인

```bash
nvidia-smi
```

`g6.xlarge`에서는 NVIDIA L4가 확인되어야 합니다.

Docker NVIDIA Runtime:

```bash
sudo docker info | grep -i runtime
```

예:

```text
Runtimes: io.containerd.runc.v2 nvidia runc
Default Runtime: runc
```

GPU Container 확인:

```bash
sudo docker run --rm --gpus all \
  nvidia/cuda:13.0.0-base-ubuntu24.04 \
  nvidia-smi
```

컨테이너 내부에서도 NVIDIA L4가 출력되면 정상입니다.

---

# Session Manager 접속

AWS Console:

```text
EC2
→ Instances
→ ubot-gpu-test
→ Connect
→ Session Manager
→ Connect
```

Instance ID는 `gpu-test output`의 `instance_id`에서 확인합니다.

SSM 상태 확인:

```powershell
docker run --rm `
  -v "$env:USERPROFILE\.aws:/root/.aws" `
  amazon/aws-cli:latest `
  ssm describe-instance-information `
  --filters "Key=InstanceIds,Values=<INSTANCE_ID>" `
  --profile ureca `
  --region ap-northeast-2 `
  --output table
```

정상:

```text
PingStatus = Online
```

---

# Security Group

현재 GPU Test Server는 외부 Inbound Rule을 기본으로 열지 않습니다.

```text
Inbound
→ 없음
→ llm_allowed_cidrs = []

Outbound
→ 0.0.0.0/0
```

추후 Backend를 연결할 때 `llm_allowed_cidrs`에 Backend의 Public IP만 `/32`로 추가합니다.

허용 Port는 `llm_port`이며 기본값은 `8000`입니다.

예:

```hcl
llm_allowed_cidrs = ["203.0.113.10/32"]
```

```text
Backend
    ↓ TCP 8000
GPU LLM Server
```

LLM API Port를 `0.0.0.0/0`에 공개하지 않습니다.

---

# Spot Instance

기본 Purchase Option은 Spot입니다.

```text
Spot Type
→ one-time

Interruption Behavior
→ terminate
```

Spot이 회수되면 인스턴스가 종료되고 Root Volume도 함께 삭제됩니다. 모델·벤치마크 결과 등 보존할 데이터는 서버에만 두지 않습니다. 회수 후에는 `apply`를 다시 실행하면 새 인스턴스가 생성됩니다.

장시간 테스트를 하거나 Spot Capacity가 부족하면 On-Demand로 전환합니다.

```powershell
.\tools\terraform\tf.ps1 `
  -Target gpu-test `
  -Command apply `
  -ExtraArgs "-var=use_spot=false"
```

인스턴스 생성이 `MaxSpotInstanceCountExceeded` 또는 `VcpuLimitExceeded`로 실패하면 GPU vCPU 할당량을 확인합니다.

---

# State 관리

```text
bootstrap
→ Local State

network
→ S3 Remote State

gpu-test
→ S3 Remote State
```

State Key:

```text
ubot/network/terraform.tfstate
ubot/gpu-test/terraform.tfstate
```

State Lock은 별도 DynamoDB 없이 S3 Lockfile을 사용합니다.

```hcl
use_lockfile = true
```

---

# State Lock 오류

비정상 종료나 인증 실패로 Lock이 남을 수 있습니다.

예:

```text
Error acquiring the state lock
```

다른 사용자가 Terraform을 실행 중이 아닌 것이 확실한 경우에만 Lock ID를 확인한 뒤 해제합니다.

```powershell
.\tools\terraform\tf.ps1 `
  -Target gpu-test `
  -Command force-unlock `
  -ExtraArgs "<LOCK_ID>"
```

Lock을 무시하는 `-lock=false` 실행은 권장하지 않습니다.

---

# AWS Login Token 만료

오류 예:

```text
ExpiredToken
RequestExpired
```

현재 Terraform 컨테이너는 `credential_process`를 통해 Credential을 필요할 때 가져오도록 구성되어 있습니다.

그래도 `aws login` 세션 자체가 만료된 경우에는 다시 로그인합니다.

```powershell
docker run --rm -it `
  -v "$env:USERPROFILE\.aws:/root/.aws" `
  amazon/aws-cli:latest `
  login `
  --profile ureca `
  --region ap-northeast-2 `
  --remote
```

---

# errored.tfstate 복구

`apply` 또는 `destroy` 중 State 저장에 실패하면 해당 Terraform Root에 `errored.tfstate`가 생성될 수 있습니다. 이 파일에는 Remote State에 반영되지 못한 상태가 들어 있을 수 있습니다.

`errored.tfstate`를 곧바로 Remote State에 `push`하지 않습니다.

다음 순서로 확인합니다.

1. AWS 로그인을 정상화합니다.
2. Stale Lock이 있으면 다른 사용자가 Terraform을 실행 중이 아닌지 확인한 뒤 해제합니다.
3. `plan`으로 현재 Remote State와 실제 AWS 리소스를 비교합니다.
4. Remote State와 AWS 실제 상태가 정상적으로 일치한다면 `errored.tfstate`는 삭제합니다.
5. Remote State가 실제 AWS 상태보다 뒤처진 것이 명확한 경우에만 `state push`를 검토합니다.

먼저:

```powershell
.\tools\terraform\tf.ps1 gpu-test plan
```

Remote State 복구가 정말 필요한 경우에만:

```powershell
.\tools\terraform\tf.ps1 `
  -Target gpu-test `
  -Command state `
  -ExtraArgs @("push", "errored.tfstate")
```

Terraform은 Remote State의 serial이 더 높거나 lineage가 다르면 `state push`를 거부합니다. 이 경우 Remote State가 더 최신이라는 뜻이므로 push하지 않습니다.

`-force`는 이 검사를 건너뛰고 Remote State를 덮어쓰므로 사용하지 않습니다.

정상 복구 후:

```powershell
Remove-Item .\infra\terraform\gpu-test\errored.tfstate
```

---

# Terraform 파일 관리

Git 제외:

```gitignore
.terraform/
*.tfstate
*.tfstate.*
*.tfplan
*.tfvars
*.auto.tfvars
backend.hcl
errored.tfstate
crash.log
crash.*.log
```

Git 포함:

```text
.terraform.lock.hcl
terraform.tfvars.example
backend.hcl.example
*.tf
scripts/*.tftpl
```

Lock 파일은 각 Root Module별로 관리합니다.

```text
bootstrap/.terraform.lock.hcl
network/.terraform.lock.hcl
gpu-test/.terraform.lock.hcl
```

`bootstrap.sh.tftpl`은 Linux에서 실행되므로 `.gitattributes`에서 LF로 고정합니다.

```gitattributes
*.tftpl text eol=lf
```

---

# 테스트 서버 삭제

GPU Server만 삭제:

```powershell
.\tools\terraform\tf.ps1 gpu-test destroy
```

`network`와 `bootstrap`은 유지됩니다.

테스트가 끝나면 GPU Server는 바로 삭제해 과금을 막습니다.

Network까지 삭제할 때는 역순으로 진행합니다.

```text
gpu-test
↓
network
```

State Bucket(`bootstrap`)은 일반 테스트 과정에서는 삭제하지 않습니다. `prevent_destroy`가 걸려 있어 `bootstrap destroy`는 실패하며, 삭제하려면 `prevent_destroy`를 해제하고 Bucket의 모든 Object Version을 먼저 비워야 합니다.

---

# 현재 Terraform 범위

완료된 범위:

```text
Terraform Docker 실행
Terraform + AWS CLI Custom Image
AWS login / credential_process 연동
S3 Remote State
S3 State Lock
VPC
Public Subnet
Internet Gateway
Route Table
GPU Security Group
IAM Role
Instance Profile
SSM
g6.xlarge Spot
AWS DLAMI
100GB gp3
IMDSv2
cloud-init
Docker
NVIDIA Container Runtime
NVIDIA L4 검증
```

현재 범위에서 제외:

```text
vLLM/SGLang 실행
LLM Model 배포
Spring LLM Client 연결
LLM Streaming
EKS
Auto Scaling
Load Balancer
RDS
Multi-AZ
```

LLM Serving Runtime은 추후 별도 `infra/llm` 구성에서 관리할 예정입니다.
