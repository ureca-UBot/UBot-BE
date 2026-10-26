# Terraform 스택

현재 UBot-BE 브랜치에는 bootstrap·network·gpu-test·aws-poc가 있습니다. 장기간 유지하는 state·네트워크, 수시로 생성하는 GPU 실험 서버, 실제 AWS PoC 자원을 별도 state로 나눕니다.

## 리소스와 경계

| 스택 | 생성하는 것 | 범위 밖 |
|---|---|---|
| [bootstrap](bootstrap/main.tf) | Terraform state용 S3, 버전 관리·AES256 암호화·공개 차단 | 애플리케이션 데이터 백업 |
| [network](network/main.tf) | VPC·public subnet 1개·IGW·route table·외부 기본 경로 | private subnet·NAT·운영 전체 네트워크 |
| [gpu-test](gpu-test/main.tf) | GPU EC2·SG·SSM IAM·instance profile·선택적 EIP | API·워커·DB·Redis·도메인·TLS·모델 자동 배포 |
| [aws-poc](aws-poc.md) | VPC·FE·BE·State·ECR·OIDC·NLB·GPU AMI·Spot ASG·선택적 On-Demand GPU | 애플리케이션 secret·도메인 DNS·GitHub environment 보호 설정 |

network와 gpu-test는 서로 다른 S3 backend key를 사용합니다. gpu-test는 remote state를 읽지 않고 VPC/subnet의 Name 태그로 찾습니다. 프로젝트 이름을 바꿨다면 `network_vpc_name`, `network_public_subnet_name`도 실제 태그와 맞춥니다.

## network·gpu-test 기본값

| 항목 | 현재 값 |
|---|---|
| 프로젝트·리전 | ubot, ap-northeast-2 |
| VPC·subnet | 10.20.0.0/16, 10.20.16.0/20 |
| AZ | ap-northeast-2a |
| GPU EC2 | g6.xlarge, 기본 Spot, 중단 시 terminate |
| AMI | DLAMI Ubuntu 24.04 NVIDIA driver의 public SSM parameter에서 조회 |
| Root disk | 암호화 gp3 100GiB, 3,000 IOPS·125MiB/s, 인스턴스 종료 시 삭제 |
| EIP | 기본 생성하지 않음 |
| API ingress | `llm_allowed_cidrs=[]`, 기본 허용 없음. 지정 시 `llm_port=8000`만 허용 |
| 관리 접속 | SSH ingress 없이 SSM |

임베딩 8001을 사용할 수 있다는 모델 설정과 이 SG의 8000 ingress는 별개입니다. 추가 연결 경로가 필요하면 실제 SG·네트워크 구성을 함께 검토합니다. output의 LLM URL은 주소를 조합한 값이며 모델 실행·접속 성공을 검증하지 않습니다.

## 기동 준비 스크립트

[`bootstrap.sh.tftpl`](gpu-test/scripts/bootstrap.sh.tftpl)은 기본 도구를 설치하고 DLAMI에 포함된 Docker·NVIDIA Container Toolkit·SSM Agent를 확인·활성화합니다. `/opt/ubot/llm`, `/opt/ubot/benchmarks`를 만들고 최종 확인 후 `.bootstrap-complete`를 남깁니다.

모델 이미지 다운로드·모델 서비스 시작은 이 스크립트에 없습니다. GPU 런타임은 [LLM 실행 문서](../llm/README.md)로 별도 준비합니다. 로그는 `/var/log/ubot-bootstrap.log`이며 user data 변경 시 인스턴스 교체를 요청하는 설정입니다.

## 준비·검사 순서

1. [Terraform 실행 이미지와 AWS 프로필](README.md#terraform-실행)을 준비합니다.
2. bootstrap으로 state bucket을 준비하고 출력값을 각 backend 설정에 넣습니다.
3. network의 init·validate·plan을 확인한 뒤 필요한 자원을 적용합니다.
4. gpu-test의 태그 조회·GPU 유형·Spot·접근 CIDR·디스크를 확인하고 plan을 검토합니다.
5. 적용 후 output의 SSM 명령과 bootstrap 로그를 확인하고 모델을 따로 시작합니다.

aws-poc는 기존 자원을 import한 전용 state와 환경별 입력 파일을 전제로 합니다. 실행 순서는 [AWS PoC 문서](aws-poc.md#실행-전-검사)를 따릅니다.

전체 명령은 [기존 Terraform 가이드](README.md)에 있습니다. state bucket은 `prevent_destroy`로 보호하며 GPU를 지워도 network와 bootstrap state가 자동 삭제되지 않습니다. GPU 인스턴스의 root volume은 종료 시 삭제되므로 실험 결과는 그 전에 외부에 보관합니다.
