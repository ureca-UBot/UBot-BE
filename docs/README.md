# UBot 기능별 문서

고객이 질문하고 서비스를 이용하는 과정, 관리자가 지식을 개선하는 과정, 배포 후 운영하는 과정을 기능별로 설명합니다. 각 기능에서 화면의 요청, 서버 처리, 저장·반환 결과, 실패 처리와 관련 코드로 이어집니다.

[백엔드 소개](../README.md) · [제품 전체 소개](https://github.com/ureca-UBot/.github/blob/main/profile/README.md) · [로컬 실행](quickstart.md)

## 기능으로 찾아보기

| 기능 | 이 문서에서 확인할 내용 |
|---|---|
| [근거 기반 AI 상담](features/ai-chat.md) | 질문 접수 → 정리·검색 → 자료·도구 조회 → 답변 검사 → 결과 저장·조회 |
| [질문과 답변 보호](features/ai-safety.md) | 입력·출력 코드 검사와 AI 판정, 관리자 후보 검증·승인 |
| [미응답에서 FAQ 개선까지](features/unanswered-improvement.md) | 수집·중복 방지·그룹화·관리자 처리·FAQ 전환·재검증 |
| [FAQ 관리와 검색 반영](features/faq-management.md) | 등록·수정 이력·질문 표현·벡터·Profile·공식 답변 직접 반환 |
| [상담사 인계와 메시지 보호](features/counselor-handoff.md) | 서버 이력 인계·대기·배정·검사·전달·종료·재상담 |
| [상품 안내와 내 이용 정보](features/products-and-subscriptions.md) | 상품 탐색·가입 변경·약정·청구·사용량·채팅 조회 도구 |
| [매장 찾기에서 방문 예약까지](features/stores-and-reservations.md) | 위치·지도 검색·길찾기·예약·취소·리마인더 |
| [회원·비회원 이용과 대화 기록](features/accounts-and-conversations.md) | JWT·게스트 세션·대화 승계·FAQ 선택 저장·이력·삭제 조건 |
| [공지·문의·알림](features/customer-support.md) | 지역 배너·공지 이력·일대일 문의·관리자 답변·읽음 처리 |
| [인기 FAQ와 지역 문의 급증 감지](features/trends-and-telemap.md) | 순위 집계·지역별 유사 문의 묶기·급증 알림·관리자 처리 |
| [배포에서 관측·장애 복구까지](features/service-operations.md) | CI/CD·운영 화면·지표·알림·복구·용량 조절 |

## 시나리오를 따라 읽기

- 고객 상담: [회원과 대화](features/accounts-and-conversations.md) → [AI 답변](features/ai-chat.md) → [상품·이용 정보](features/products-and-subscriptions.md) 또는 [매장 방문](features/stores-and-reservations.md) → [상담사 연결](features/counselor-handoff.md)
- 지식 개선: [미응답 수집](features/unanswered-improvement.md) → [FAQ 수정과 검색 반영](features/faq-management.md) → [입력·출력 보호](features/ai-safety.md) → [문의 추이 확인](features/trends-and-telemap.md)
- 서비스 운영: [배포·관측·복구](features/service-operations.md) → [장애별 런북](runbooks/README.md) → [장애 검증 기록](evidence/fault-recovery-20261023.md)

## 실행·연동·운영 안내

| 하려는 일 | 안내 |
|---|---|
| 서버와 데이터를 처음 준비 | [Quickstart](quickstart.md), [로컬 데이터](how-to/local-data.md), [DB 초기화](how-to/reset-local-db.md) |
| 프론트엔드에서 API 호출 | [연동 가이드](how-to/frontend-integration.md), [오류 코드](reference/error-codes.md) |
| 모델·임베딩 엔진 연결 | [LLM 모듈](how-to/llm-module.md), [모델 서빙](../infra/llm/README.md), [가드 연결](how-to/ai-guard-integration.md) |
| 상담 기능 실행·관리 | [상담 API와 초기 색인](how-to/counsel.md) |
| 관리자 위험 표현 분석 실행 | [검토 절차](how-to/guard-review.md), [분석 모델 실행](how-to/admin-risk-runtime.md) |
| 배포와 인프라 확인 | [배포](deploy.md), [Terraform](../infra/terraform/README.md) |
| 운영 상태 확인·장애 대응 | [관측](operations/observability.md), [지표](reference/metrics.md), [알림](how-to/alerting.md), [런북](runbooks/README.md) |
| 실패·과부하의 처리 경계 확인 | [신뢰성 모델](reliability-model.md), [Worker 복구](how-to/worker-reliability.md) |
| 설정·실행 오류 해결 | [설정 Reference](reference/configuration.md), [Troubleshooting](troubleshooting.md), [검증 범위](support-policy.md) |

## 실험과 설계 근거

| 확인할 판단 | 기록 |
|---|---|
| 임베딩 모델과 검색 구성을 왜 골랐는가 | [실험과 BE 구현 대응](features/faq-management.md#임베딩-실험과-be-반영) |
| 검색 임계값을 어떻게 비교했는가 | [FAQ 유사도 임계값](FAQ_Threshold_테스트_보고서.md) |
| 질문·답변 벡터와 검색 방식을 어떻게 비교했는가 | [FAQ 검색·Q+A](FAQ_검색_테스트_보고서.md), [HNSW](FAQ_HNSW_검색_테스트_보고서.md), [Reranker](FAQ_Reranker_테스트_보고서.md) |
| 질문 분리·멀티턴 처리를 어떻게 확인했는가 | [질문 분리·멀티턴 검증](질문분리_멀티턴_테스트_보고서.md) |
| 입력·출력 파이프라인과 가드를 어떻게 평가했는가 | [파이프라인 검증](pipeline-test-feat277-20261023.md), [출력 가드 분석](output-guard-analysis.md), [출력 LLM 가드 비교](pr291-output-llm-guard-comparison.md), [검증 질문](pr291-output-llm-guard-test-questions.md) |
| 미응답 후속 처리를 왜 분리했는가 | [Outbox ADR](미응답_저장_Outbox_ADR.md) |
| 의존 서비스가 멈췄을 때 무엇을 확인했는가 | [실제 LLM 장애 복구 실험](evidence/fault-recovery-20261023.md) |

실험 결과는 측정 당시 코드·모델·데이터·환경의 기록입니다. 현재 기본값과 다른 값도 원본 결과로 보존합니다.

## 내부 구조와 명세

- [코드 구조와 요청 흐름](architecture.md)
- [DB 직접 조회와 ERD](how-to/db-access.md)
- [설계 그림 모음](images/README.md)
- [프롬프트](../src/main/resources/prompts/README.md)
- [예외 작성 규칙](../src/main/java/com/ubot/common/manual/exception.md)
- [개발·PR 규칙](../CONTRIBUTING.md)
- 로컬 실행 중 API 명세: `/swagger-ui.html`, `/v3/api-docs`. 운영 프로필에서는 비활성화합니다.
