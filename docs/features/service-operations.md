# 배포에서 관측·장애 복구까지

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [운영 런북](../runbooks/README.md)

개발자가 변경을 검증하고 배포하면 운영자는 서비스와 의존 시스템의 상태를 확인합니다. 장애가 나면 알림에서 관련 지표와 작업 기록을 찾아 복구하고, 기존 요청이 어떻게 끝났는지 확인합니다.

## 변경을 서비스에 반영하기

1. GitHub Actions CI가 대상 브랜치의 PR·push에서 빌드·테스트와 배포 구성 검사를 수행합니다.
2. AWS 배포가 활성화되어 있고 배포 브랜치의 push CI가 성공하면 해당 commit의 이미지를 ECR에 발행하고 digest를 고정합니다. PR 검사 성공만으로 배포하지 않습니다.
3. SSM으로 BE EC2의 배포를 실행합니다. API의 Flyway 적용과 기동, Nginx, Worker, 상담 초기 색인, 관측 대상 확인으로 이어집니다.
4. API health·공개 조회·Worker 기동·수집 대상을 확인합니다. 이전 배포 실행이 뒤늦게 최신 코드를 덮지 않도록 대상 commit도 확인합니다.

API·Worker는 BE 이미지를 공유하고, State DB·Redis와 GPU 모델 서버는 다른 역할로 구성합니다. 이 CD가 FE·State·GPU Auto Scaling Group을 새로 만들거나 용량을 변경하지는 않습니다. 정확한 실행 순서·수동 배포·복구 방법은 [배포 안내](../deploy.md)에 있습니다.

## 평상시 관측과 장애 알림

운영 화면은 서비스 상태와 endpoint 지표를 보여 주고, FAQ 운영 화면은 사용 현황과 지식 개선 대상을 보여 줍니다. 모델 지연·작업 대기·DB 연결·Redis 상태는 Prometheus 지표로 구분합니다.

알림은 Prometheus 규칙에서 Alertmanager를 거쳐 설정한 Discord·Slack으로 전달합니다. 알림 이름에 맞는 런북에서 읽기 전용 진단, 조치, 복구 확인을 이어갑니다. 채팅은 jobId와 답변 시도 ID로 API·Worker·DB 결과를 연결해 추적합니다.

| 운영 질문 | 확인할 문서 |
|---|---|
| 요청은 받는데 답변이 늦는가 | [관측과 진단](../operations/observability.md), [큐 정체 대응](../runbooks/chat-queue-stalled.md) |
| 어떤 지표로 API·Worker·모델을 구분하는가 | [지표와 운영 API](../reference/metrics.md) |
| 알림은 어디로 보내고 어떻게 확인하는가 | [장애 알림 구성](../how-to/alerting.md) |
| DB·Redis·모델이 멈추면 작업은 어떻게 되는가 | [신뢰성 모델](../reliability-model.md) |
| 배포 전후 무엇을 확인하는가 | [배포](../deploy.md), [실행·검증 범위](../support-policy.md) |

## 처리 중 장애가 나면

- DB에 접수한 작업이 원본입니다. Redis 발행이 실패하면 저장된 작업을 기준으로 재발행합니다.
- Worker가 멈추면 heartbeat와 실행 소유권을 확인해 회수합니다. 이전 실행이 늦게 돌아와도 새 실행의 결과를 덮지 못합니다.
- 모델 연결 실패는 작업 기한 안에서 재시도합니다. 기한과 전달 횟수를 모두 무한히 늘리지는 않습니다.
- DB 장애 중 작업 조회는 제한된 예산 안에서 503을 반환합니다. 결과를 저장하지 못한 작업은 성공으로 ACK하지 않습니다.
- 복구 판단은 health뿐 아니라 기존 작업과 새 요청의 최종 상태까지 확인합니다. 자동 복구가 모델 계산을 정확히 한 번만 실행한다는 보장은 없습니다.

## 용량과 비용을 조절하는 지점

공식 FAQ 직접 반환은 답변 생성 호출을 줄이고, API와 AI 처리의 분리는 자원을 서로 다른 기준으로 조절할 수 있게 합니다. Worker 동시 실행 수는 모델 처리량·DB pool과 함께 조절해야 하며, 숫자만 올린다고 GPU 용량이 늘어나지는 않습니다.

Terraform에는 AWS 네트워크·서버·GPU 구성 정의가 있습니다. 배포 파일이 있다는 사실과 해당 자원이 상시 실행 중이라는 것은 구분해야 합니다. 실제 절감액은 실행 시간·인스턴스·트래픽을 측정해야 하므로 설계만으로 금액을 단정하지 않습니다. 구성은 [Terraform 안내](../../infra/terraform/README.md)에서 확인합니다.

## 관련 코드와 검증

- 자동 배포: [CI](../../.github/workflows/ci.yml), [AWS BE CD](../../.github/workflows/aws-be-deploy.yml)
- 실행·복구: [ChatJobRunner](../../src/main/java/com/ubot/chat/job/worker/ChatJobRunner.java), [ChatJobRecoveryService](../../src/main/java/com/ubot/chat/job/worker/ChatJobRecoveryService.java)
- 운영 조회: [ServiceOperationMonitoringController](../../src/main/java/com/ubot/monitoring/operationmonitoring/controller/ServiceOperationMonitoringController.java)
- 복구 계약과 테스트 경로: [Worker 장애 복구](../how-to/worker-reliability.md)
- 측정 기록: [실제 LLM 장애 복구 실험](../evidence/fault-recovery-20261023.md). 기록된 조건과 수치를 보존하며, 현재 운영의 복구 시간 보장으로 해석하지 않습니다.
