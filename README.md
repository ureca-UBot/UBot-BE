# UBot-BE

UBot은 고객이 통신 상품을 찾고, 자신의 이용 정보를 확인하고, 매장 방문과 상담을 이어갈 수 있는 통신 생활 서비스입니다. 이 저장소는 회원·상품·매장·상담·관리자 업무를 처리하고, 공식 FAQ와 조회 자료를 바탕으로 AI 답변을 만드는 백엔드입니다.

[서비스 이용](https://u-bot.cloud) · [제품 전체 소개](https://github.com/ureca-UBot/.github/blob/main/profile/README.md) · [프론트엔드](https://github.com/ureca-UBot/UBot-FE) · [기능별 문서](docs/README.md) · [실행 안내](docs/quickstart.md)

## 고객의 질문을 다음 행동으로 연결합니다

고객은 상품·이용 정보 화면에서 필요한 내용을 확인하거나 AI에게 질문할 수 있습니다. AI는 관련 FAQ를 찾고, 필요한 경우 본인 이용 정보·상품·매장을 조회해 답변과 화면 카드를 제공합니다. 매장 방문이 필요하면 길찾기와 예약으로, 사람의 도움이 필요하면 같은 대화의 기록을 가진 상담사에게 이어집니다.

<p align="center">
  <picture>
    <source media="(max-width: 767px)" srcset="docs/images/current/service-flow-mobile.svg">
    <img src="docs/images/current/service-flow.svg" alt="고객 질문에서 근거 기반 답변, 미응답 개선과 상담사 인계로 이어지는 UBot 흐름" width="90%">
  </picture>
</p>

- [근거 기반 AI 상담](docs/features/ai-chat.md): 질문 정리·임베딩·FAQ 검색·자료 조회·답변 검사를 거쳐 결과를 제공합니다.
- [상품 안내와 내 이용 정보](docs/features/products-and-subscriptions.md): 상품 탐색과 가입·변경, 회선별 약정·청구·사용량 조회를 연결합니다.
- [매장 찾기와 방문 예약](docs/features/stores-and-reservations.md): 지역·지도·현재 위치 검색에서 길찾기·방문 시간 선택·예약으로 이어집니다.
- [상담사 인계와 메시지 보호](docs/features/counselor-handoff.md): 서버에 저장된 문답·FAQ 선택·이전 상담 기록으로 상담을 이어가고, 검사한 메시지만 전달합니다.

## 답하지 못한 질문으로 지식을 보완합니다

근거가 부족한 질문을 비슷한 문의끼리 묶고, 관리자가 FAQ를 등록하거나 수정하도록 연결합니다. 바뀐 FAQ는 검색에 반영하고 이전 미응답과 다시 비교합니다. 관리자는 사용 현황과 지역별 문의 증가도 함께 보고 개선할 내용을 정할 수 있습니다.

- [미응답 수집·그룹화·FAQ 전환·재검증](docs/features/unanswered-improvement.md)
- [FAQ 등록·수정 이력·질문 표현·임베딩 색인](docs/features/faq-management.md)
- [인기 FAQ와 지역 문의 급증 감지](docs/features/trends-and-telemap.md)
- [입력·출력 검사와 관리자 안전 정책 검토](docs/features/ai-safety.md)

## 배포 이후에도 처리 결과를 확인합니다

코드 변경은 CI 검증을 거쳐 배포하고, 운영 화면·지표·로그·알림으로 서비스 상태를 확인합니다. 작업을 DB에 남겨 Worker·Redis·모델 장애 후 복구할 수 있게 하고, 복구가 끝났는지는 기존 요청의 최종 결과까지 확인합니다.

공식 FAQ 직접 반환은 답변 생성 호출을 줄입니다. API와 AI 처리를 분리해 필요한 자원을 조절할 수 있으며, 실제 처리량과 비용은 모델·인프라·부하 조건을 함께 확인합니다.

[배포·관측·장애 복구 흐름](docs/features/service-operations.md) · [장애 실험 결과](docs/evidence/fault-recovery-20261023.md)

## 전체 기능

| 기능 | 고객·관리자가 할 수 있는 일 | 상세 문서 |
|---|---|---|
| AI 상담 | 여러 질문과 후속 질문에 대해 근거를 검색하고 답변·조회 카드를 확인 | [질문에서 답변까지](docs/features/ai-chat.md) |
| 질문·답변 보호 | 위험한 입력·출력을 검사하고 관리자 승인으로 정책 개선 | [가드와 안전 정책](docs/features/ai-safety.md) |
| 미응답 개선 | 반복되는 미응답을 검토하고 FAQ로 전환·재검증 | [미응답 개선 흐름](docs/features/unanswered-improvement.md) |
| FAQ 관리 | 공식 답변·질문 표현·카테고리·버전·검색 품질 관리 | [FAQ와 검색 반영](docs/features/faq-management.md) |
| 상담사 연결 | 같은 대화에서 상담 요청·배정·메시지 교환·종료·재상담 | [인계와 메시지 보호](docs/features/counselor-handoff.md) |
| 상품·이용 정보 | 상품 비교·가입·변경과 본인 회선의 약정·청구·사용량 확인 | [상품과 이용 정보](docs/features/products-and-subscriptions.md) |
| 매장·예약 | 매장 탐색·길찾기·예약·취소와 관리자 방문 상태 처리 | [매장 방문 흐름](docs/features/stores-and-reservations.md) |
| 회원·대화 기록 | 로그인, 게스트 대화 승계, 이전 문답·FAQ 선택·상담 이력 조회 | [회원과 대화 기록](docs/features/accounts-and-conversations.md) |
| 공지·문의·알림 | 지역 공지·장애 배너, 일대일 문의·답변, 예약·이용 알림 확인 | [고객 지원 흐름](docs/features/customer-support.md) |
| 인기·지역 문의 | 인기·급상승 FAQ 조회, 지역 유사 문의 급증 확인·처리 | [문의 흐름 파악](docs/features/trends-and-telemap.md) |
| 운영 | 배포, 서비스 지표 확인, 장애 알림·진단·복구 | [서비스 운영](docs/features/service-operations.md) |

## 설계와 검증

| 설계 판단 | 동작과 근거 |
|---|---|
| 공식 지식과 실제 조회 자료로 답변 | 검색·문장 근거·출력 검사를 거칩니다. [AI 상담 흐름](docs/features/ai-chat.md), [가드 검증](docs/features/ai-safety.md#관련-코드와-검증) |
| 임베딩 모델·검색 방식 선택 | 실험과 현재 설정을 구분합니다. [임베딩 실험과 BE 반영](docs/features/faq-management.md#임베딩-실험과-be-반영), [HNSW 비교](docs/FAQ_HNSW_검색_테스트_보고서.md) |
| 모델에 업무 변경 권한을 주지 않음 | 조회 도구는 자료만 제공하며 가입·예약은 인증·소유권·업무 조건을 검사하는 API가 확정합니다. [상품 처리](docs/features/products-and-subscriptions.md) |
| 작업 상태와 결과를 DB에 보존 | Redis 전달 실패와 Worker 종료 뒤 복구하며 이전 실행의 늦은 결과를 거절합니다. [복구 계약](docs/reliability-model.md), [실험 기록](docs/evidence/fault-recovery-20261023.md) |
| 미응답 저장을 후속 작업으로 처리 | Outbox에 저장할 일을 남겨 고객 응답과 후속 처리의 실패를 구분합니다. [설계 근거](docs/미응답_저장_Outbox_ADR.md) |

기존 실험 수치는 측정 당시 환경의 결과입니다. 현재 제품의 모든 요청에 대한 성능 보장은 아니며, [검증 범위](docs/support-policy.md)와 함께 확인합니다. 실제 통신망 개통·결제 원장 연동과 다중 리전 재해 복구는 현재 구현 범위에 포함하지 않습니다.

## 기술 구성

| 영역 | 기술과 역할 |
|---|---|
| API·업무 처리 | Java 21, Spring Boot 4.1.1, Spring Security·JWT |
| 데이터 | PostgreSQL 18, pgvector 벡터 검색, PostGIS 공간 검색, Flyway |
| 작업 전달·캐시 | Redis 8, Streams 작업 신호, 순위 스냅샷, 분산 잠금 |
| AI | BGE-M3 임베딩, Ollama·vLLM 연결, Spring AI 2.0.1, 서버가 허용한 조회 도구 |
| 외부 정보 | 카카오 위치·길찾기 API, MaxMind GeoLite2 지역 판별 |
| 운영 | Docker Compose, Nginx, GitHub Actions, ECR·SSM, Micrometer·Prometheus·Alertmanager |
| 인프라 | Terraform AWS 구성, GPU 답변·임베딩 서빙 |

## 바로 실행해 보기

Gradle 실행용 JDK 17 이상, 실행 중인 Docker Desktop, Git이 필요합니다. 빌드에는 Java 21 toolchain을 사용하며 없으면 Gradle이 내려받습니다.

```powershell
Copy-Item .env.example .env     # POSTGRES_PASSWORD와 JWT_SECRET을 수정
.\tools\ubot.ps1 up
.\gradlew.bat bootRun
```

`http://localhost:8080/actuator/health`의 status가 UP이면 애플리케이션 상태를 확인한 것입니다. 로컬 API 명세는 `http://localhost:8080/swagger-ui.html`에서 확인합니다. 실제 채팅 답변에는 모델과 FAQ 데이터도 준비해야 합니다.

[처음부터 실행하기](docs/quickstart.md) · [FAQ·모델 데이터 준비](docs/how-to/local-data.md) · [설정값](docs/reference/configuration.md) · [실행 문제 해결](docs/troubleshooting.md)

테스트는 Docker가 실행 중인 환경에서 다음 명령으로 실행합니다. 기본 테스트는 실제 LLM 품질 평가와 구분합니다.

```powershell
.\gradlew.bat test
```

## 더 알아보기

- [기능별 문서와 실험 기록 전체](docs/README.md)
- [프론트엔드 API 연동](docs/how-to/frontend-integration.md) · [오류 코드](docs/reference/error-codes.md)
- [코드 구조와 내부 처리](docs/architecture.md) · [DB와 ERD](docs/how-to/db-access.md)
- [배포 절차](docs/deploy.md) · [Terraform 인프라](infra/terraform/README.md) · [모델 서빙](infra/llm/README.md)
- [개발·PR 규칙](CONTRIBUTING.md)
