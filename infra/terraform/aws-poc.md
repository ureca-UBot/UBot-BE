# AWS CD와 GPU 자원 구성

[AWS BE CI/CD](../../docs/deploy.md#aws-cicd-흐름)는 OIDC로 AWS에 인증하고 ECR 이미지와 SSM으로 기존 BE를 배포합니다. 네트워크·서버·배포 권한과 GPU 확장 구성은 Terraform으로 관리합니다.

실행 소스는 [`infra/terraform/aws-poc`](aws-poc/)에 있습니다. `tf.ps1`의 target으로 `aws-poc`을 지정해 fmt·validate·plan을 실행할 수 있습니다. 실제 적용 전에는 원격 state와 대상 계정의 plan을 반드시 확인합니다.

## 서버 구성

![AWS BE CD와 NLB·GPU ASG 구성](../../docs/images/current/aws-gpu-topology.svg)

NLB가 채팅·임베딩 요청을 GPU 모델 서버에 전달합니다. Java `ai-worker`는 BE EC2에서 실행하며, ASG는 GPU 추론 인스턴스 수를 조절합니다.

| 구성 | 역할 |
|---|---|
| 스폿 GPU ASG | `gpu.tf`의 시작 템플릿으로 GPU를 생성하고 평균 GPU 사용률 60%를 기준으로 대수를 조절합니다. 채팅·임베딩 대상 그룹에 자동 등록합니다. |
| 별도 온디맨드 GPU | `gpu_on_demand.tf`의 단일 EC2입니다. 같은 GPU AMI를 사용하며, 평소에는 채팅·임베딩을 처리합니다. 관리자 14B 분석 중에는 `switcher.py`가 NLB 대상에서 제외하고 분석이 끝나면 다시 등록합니다. |

ASG의 `on_demand_base_capacity=0`, `on_demand_percentage_above_base_capacity=0`은 ASG를 스폿만으로 구성하는 설정입니다. 별도 온디맨드 GPU의 생성 여부는 `gpu_on_demand_enabled`로 정합니다. 온디맨드가 관리자 분석을 처리하는 동안에도 채팅을 받으려면 스폿 GPU를 최소 1대 유지합니다.

## 구성 파일

| 파일 | 정의하는 것 |
|---|---|
| `ecr.tf` | `ubot-be-aws-poc` ECR, 태그 덮어쓰기 금지, 최근 20개를 넘는 이미지 정리. 업로드 시 자동 검사는 비활성화 |
| `network.tf`, `servers.tf`, `security_groups.tf` | VPC·서브넷·라우팅과 FE·BE·State EC2, 서비스별 보안 그룹 |
| `iam_github.tf` | GitHub OIDC 공급자, BE·FE CD 역할. 기본 허용 브랜치는 develop이며 BE는 aws-poc 환경도 허용 |
| `iam_instances.tf` | BE의 SSM·ECR 이미지 읽기 권한과 인스턴스 프로파일, GPU의 SSM·CloudWatch 권한 |
| `nlb.tf` | 내부 NLB, 채팅 8000·임베딩 8001의 TCP 리스너·대상 그룹 |
| `gpu.tf`, `gpu_ami.tf` | GPU AMI 빌더, 시작 템플릿, 스폿 ASG와 GPU 사용률 목표 추적 정책 |
| `gpu_on_demand.tf` | 선택적 온디맨드 GPU와 관리자 분석 모드 전환 파일 배포 |
| `files/` | GPU AMI·CloudWatch 에이전트·온디맨드 GPU 초기 설정 파일 |
| `variables.tf`, `outputs.tf` | CD 대상 인스턴스 ID와 GPU 용량 입력, 배포 연결용 출력 |
| `versions.tf`, `backend.hcl.example` | Terraform 1.10 이상 2 미만, AWS 공급자 6.x, S3 원격 상태 저장소 |

기존 AWS 자원을 가져온 원격 state를 사용하며 FE·BE·State에는 `prevent_destroy`가 설정돼 있습니다. `terraform.tfvars`와 `backend.hcl`은 환경별 파일이므로 Git에 넣지 않습니다.

## 실행 전 검사

[Docker 실행 이미지와 AWS 프로필](README.md#사전-준비)을 준비한 뒤 저장소 루트에서 예제 설정을 복사합니다. `backend.hcl`의 버킷·key는 기존 자원을 가져온 원격 state에 맞추고, `terraform.tfvars`에는 실제 자원 값을 넣습니다.

```powershell
Copy-Item .\infra\terraform\aws-poc\backend.hcl.example .\infra\terraform\aws-poc\backend.hcl
Copy-Item .\infra\terraform\aws-poc\terraform.tfvars.example .\infra\terraform\aws-poc\terraform.tfvars

.\tools\terraform\tf.ps1 aws-poc init '-backend-config=backend.hcl' '-lockfile=readonly' '-input=false'
.\tools\terraform\tf.ps1 aws-poc fmt -check -recursive
.\tools\terraform\tf.ps1 aws-poc validate
.\tools\terraform\tf.ps1 aws-poc plan '-input=false' '-detailed-exitcode' '-out=aws-poc.tfplan'
```

`terraform.tfvars`의 BE·FE 인스턴스 ID, GPU AMI ID, 스폿 최소·최대 대수를 확인합니다. `gpu_builder_enabled`와 `gpu_on_demand_enabled`의 기본값은 `false`입니다. 온디맨드를 켤 때는 `infra/guard-review-runtime`의 제어기·전환기·Compose 파일과 GPU 할당량을 확인합니다.

`-detailed-exitcode`를 사용한 plan의 종료 코드는 변경 없음 `0`, 오류 `1`, 변경 있음 `2`입니다. 저장한 계획은 `.\tools\terraform\tf.ps1 aws-poc show aws-poc.tfplan`으로 확인합니다.

## 검증 결과 — 2026-10-26

Docker의 Terraform 1.16.4와 AWS 공급자 6.70.0으로 검사했습니다. 실제 환경의 backend·변수 파일을 사용해 서울 리전의 원격 state와 AWS 자원을 조회했습니다.

| 검사 | 결과 |
|---|---|
| `fmt -check -recursive` | 통과 |
| `validate -json` | 유효함, 오류 0건·경고 0건 |
| `plan -detailed-exitcode` | 정상 완료, 종료 코드 2. 생성 0건·변경 1건·삭제 0건 |

변경 대상은 `aws_launch_template.gpu`의 `user_data`입니다. `files/gpu-cwagent.sh`의 주석 두 줄을 한국어로 바꾸면서 파일 전체를 인코딩한 값이 달라졌습니다. 주석을 제외한 실행 코드는 같고 AMI도 동일합니다. 적용 시 시작 템플릿의 새 버전과 기본 버전이 갱신되며, ASG는 `$Default`를 참조하므로 이후 새로 생성하는 GPU가 그 버전을 사용합니다.

검증 시 ASG의 최소·최대·희망 대수는 모두 0이고 변경 계획도 없었습니다. 별도 온디맨드 GPU와 AMI 빌더는 비활성화 상태였습니다. 이 계획은 적용하지 않았습니다.

## GPU 확장·준비·제거

| 항목 | 코드에 정의된 값 |
|---|---|
| 인스턴스·디스크 | g6.xlarge, 암호화 gp3. 스폿은 AMI 루트 디스크 크기, 빌더 기본 50GiB, 온디맨드 기본 70GiB |
| 시작 방식 | Docker·NVIDIA·vLLM·모델 캐시와 자동 시작을 준비한 AMI를 입력받음 |
| 사용자 데이터 | 모델 설치 대신 CloudWatch 에이전트 설정 |
| 스폿 정책 | ASG 내 온디맨드 기본 대수·추가 비율 모두 0, `price-capacity-optimized` |
| ASG 크기 | `gpu_min_size`~`gpu_max_size` 입력값 사용, 최대 3대. 예시는 0/0 |
| 확장 기준 | `CWAgent/nvidia_smi_utilization_gpu`, ASG별 평균, 목표 60% |
| 대기·교체 | 준비 시간 420초, ELB 상태 검사 유예 1,200초, 용량 재조정 활성화 |
| AMI 볼륨 초기화 | 초기화 속도 300MiB/s |
| 모델 상태 검사 | HTTP `/health`, 10초 간격·6초 제한·정상 3회·실패 2회 |
| 대상 해제 | 연결 종료 유예 120초 |

GPU는 NAT가 없는 환경에서 SSM·에이전트가 외부에 연결할 수 있도록 `public_2a` 서브넷과 공인 IP를 사용합니다. NLB는 `private_2a`·`private_2b`의 내부 주소를 사용합니다. `security_groups.tf`는 NLB의 채팅 8000을 워커 보안 그룹에, 임베딩 8001을 API·워커 보안 그룹에 허용합니다. GPU의 8000·8001은 NLB 보안 그룹에서만 받습니다.

NLB의 교차 영역 부하 분산은 비활성화돼 있습니다. 모델 준비 시각·가용 영역별 정상 대상·클라이언트 재시도를 함께 확인합니다.

## GPU 중지

스폿 GPU를 꺼 둘 때는 `gpu_min_size=0`, `gpu_max_size=0`을 사용합니다. 별도 온디맨드 GPU와 AMI 빌더는 각각 `gpu_on_demand_enabled`, `gpu_builder_enabled`로 관리합니다.

기존 부하 실험의 4·16·32·64·128은 한 워커의 동시 작업 수입니다. ASG 서버 수별 처리량이나 비용 비교 결과는 아닙니다.

## 배포 권한·연결 값

BE CD 역할은 지정 ECR에 이미지 업로드와 지정 BE 인스턴스에 대한 `AWS-RunShellScript` 실행을 허용합니다. FE 역할도 지정 FE 인스턴스로 범위를 제한합니다. OIDC 주체 문자열은 조직·저장소의 불변 ID를 포함하는 형식을 사용하며, OIDC 공급자에는 삭제 방지 설정이 있습니다.

출력 `ecr_repository_url`, `be_cd_role_arn`, `fe_cd_role_arn`, `ai_nlb_dns_name`, `gpu_asg_name`을 연결 설정에 사용합니다. BE에는 `AI_MODE=vllm`, `AI_RUNTIME_MANAGED=false`, `LLM_BASE_URL=http://<NLB DNS>:8000/v1`, `EMBEDDING_BASE_URL=http://<NLB DNS>:8001/v1`을 지정합니다. 모델 이름은 `LLM_MODEL=ubot-chat`, `EMBEDDING_MODEL=ubot-embedding`으로 맞춥니다.

[AWS BE CD](../../docs/deploy.md#aws-cicd-흐름)는 BE EC2의 API·워커·Nginx·관측 도구를 교체합니다. 기존 [SSH 수동 CD](../../docs/deploy.md#기존-sshscp-수동-cd)도 별도 파일로 남아 있습니다. GitHub 배포 활성화와 환경 승인 규칙은 [배포 문서](../../docs/deploy.md#aws-cicd-흐름)를 따릅니다.
