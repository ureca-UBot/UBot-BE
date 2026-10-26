# UBot-BE 문서

이 저장소의 문서는 제품 소개를 반복하지 않고, 백엔드를 실행·연동·검증·운영할 때 필요한 근거로 이어집니다. UBot 전체 사용자 흐름은 [조직 제품 문서](https://github.com/ureca-UBot/.github/blob/main/docs/README.md), 백엔드의 실제 계약은 이 저장소의 코드·설정·마이그레이션과 아래 문서에서 확인합니다.

## 목적별로 읽기

| 하려는 일 | 먼저 볼 문서 | 확인할 결과 |
|---|---|---|
| 로컬에서 서버를 처음 실행 | [Quickstart](quickstart.md) | 애플리케이션과 DB health가 `UP` |
| 프론트엔드에서 API 연동 | [프론트엔드 연동](how-to/frontend-integration.md) · [오류 코드](reference/error-codes.md) | 인증·비동기 작업·재시도·폴링 계약 |
| 질문이 답변으로 바뀌는 과정 이해 | [아키텍처](architecture.md#챗봇-질문-처리-흐름) · [LLM 모듈](how-to/llm-module.md) | 입력 검사부터 검색·도구·가드·저장까지의 흐름 |
| 상담사 인계 연동 | [상담사 연결](how-to/counsel.md) | AI 대화 이력 인계, 메시지 보호, 종료·재상담 |
| FAQ와 임베딩 데이터 준비 | [로컬 데이터](how-to/local-data.md) · [DB 초기화](how-to/reset-local-db.md) | FAQ 원문·variant·현재 Profile 벡터 준비 |
| 설정값 변경 | [설정 Reference](reference/configuration.md) | 기본값, 적용 시점, 재시작·부작용 확인 |
| 장애와 과부하 동작 확인 | [신뢰성 모델](reliability-model.md) | 감지 신호, 시스템 동작, 복구·중단 조건 |
| 운영 상태 진단 | [관측과 진단](operations/observability.md) · [지표 Reference](reference/metrics.md) | 질문별 지표·로그·조회 경로 |
| 장애 대응 | [런북 안내](runbooks/README.md) | 읽기 전용 진단, 조치, 복구 확인, 금지 작업 |
| 배포와 롤백 | [배포](deploy.md) · [Terraform](../infra/terraform/README.md) | 이미지·Compose·SSM 배포와 AWS 리소스 경계 |
| 현재 검증 범위 판단 | [지원·검증 범위](support-policy.md) | 확인한 환경과 아직 보장하지 않는 범위 |

## 기능 흐름

| 기능 | 동작 설명 | 세부 문서 |
|---|---|---|
| FAQ 기반 AI 답변 | 질문을 접수해 비동기 작업으로 저장하고, FAQ 검색·문장 근거·도구 호출·출력 검사를 거쳐 결과를 DB에 남김 | [아키텍처](architecture.md) · [Worker 복구](how-to/worker-reliability.md) |
| 입력·출력 보호 | 금지어·프롬프트 공격·개인정보를 입력에서 검사하고, 모델 출력은 규칙·AI 가드로 재검사 | [AI 가드](how-to/ai-guard-integration.md) · [Guard Review](how-to/guard-review.md) |
| 미응답 지식 개선 | 근거가 없거나 답하지 못한 질문을 Outbox로 저장해 묶고, FAQ 등록·재검증으로 연결 | [Outbox ADR](미응답_저장_Outbox_ADR.md) |
| 상담사 인계 | 로그인 회원의 서버 저장 대화와 FAQ 선택을 상담사에게 넘기고 상담 메시지를 보호 | [상담](how-to/counsel.md) |
| 상품·고객·매장 도구 | 인증 사용자 자료와 상품·매장·경로 데이터를 서버가 조회해 LLM 참고 자료와 화면 데이터로 제공 | [아키텍처](architecture.md) |
| 운영 복구 | DB를 작업 원본으로 두고 Redis·Worker·모델 장애 뒤 재발행·회수·재시도를 제한 시간 안에서 수행 | [신뢰성 모델](reliability-model.md) · [장애 검증 기록](evidence/fault-recovery-20261023.md) |

## Reference

- [환경변수와 실행 설정](reference/configuration.md)
- [오류 코드와 클라이언트 처리](reference/error-codes.md)
- [Prometheus 지표와 운영 API](reference/metrics.md)
- 실행 중인 서버의 요청·응답 스키마: `/swagger-ui.html`, `/v3/api-docs`
- DB 스키마 원본: `src/main/resources/db/migration/`
- 프롬프트 원본: `src/main/resources/prompts/`

## 실험과 검증 기록

실험 결과는 측정 당시 코드·모델·환경에만 적용합니다. 현재 기본값이나 운영 성능을 자동으로 보장하지 않습니다.

- [FAQ 유사도 임계값](FAQ_Threshold_테스트_보고서.md)
- [FAQ 검색·Q+A 벡터 비교](FAQ_검색_테스트_보고서.md)
- [HNSW 검색 비교](FAQ_HNSW_검색_테스트_보고서.md)
- [Reranker 비교](FAQ_Reranker_테스트_보고서.md)
- [질문 분리·멀티턴 검증](질문분리_멀티턴_테스트_보고서.md)
- [출력 가드 분석](output-guard-analysis.md)
- [장애 복구 검증](evidence/fault-recovery-20261023.md)

## 문서를 읽을 때의 기준

- API 필드·기본값·스키마가 문서와 다르면 현재 코드, `.env.example`, `application.yml`, Flyway 마이그레이션을 기준으로 확인합니다.
- 실행 명령에는 성공 여부를 판정할 관찰 결과가 함께 있어야 합니다.
- 성능·호환성·복구 주장은 측정 환경과 테스트 경로가 연결된 범위에서만 해석합니다.
- 장애 설명은 재시도 가능 여부, 중단 조건과 호출자가 해야 할 일을 함께 확인합니다.
- 삭제·초기화·마이그레이션 명령은 영향 범위와 되돌리기 가능성을 먼저 확인합니다.
