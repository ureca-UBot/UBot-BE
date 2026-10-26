# 근거 기반 AI 상담

[전체 기능](../README.md) · [입력·출력 보호](ai-safety.md) · [상담사 연결](counselor-handoff.md)

고객이 통신 서비스에 관해 질문하면 등록된 FAQ에서 근거를 찾고, 필요한 상품·가입 정보·매장 자료를 조회해 답합니다. 화면은 답변뿐 아니라 조회한 자료를 카드로 보여 줄 수 있습니다. 여러 질문을 한 번에 묻거나 이전 답변에 이어 질문하는 경우도 같은 처리 흐름에서 다룹니다.

## 질문에서 답변까지

1. 화면이 질문, 현재 대화 ID와 선택적인 위치를 보냅니다. 서버는 회원 또는 게스트를 식별하고 대화 소유권·이용 한도·입력 규칙을 확인합니다.
2. 서버가 답변 시도와 작업을 PostgreSQL에 저장하고 커밋 뒤 Redis Streams로 처리 신호를 보냅니다. 화면에는 202와 jobId를 반환합니다.
3. Worker가 작업을 선점합니다. 질문 분리·멀티턴 표지가 없으면 원문으로 먼저 검색하고, 표지가 있거나 원문 검색이 부족하면 질문 정리 단계에서 필요한 검색어를 만듭니다. 이전 대화는 필요한 경우에만 사용합니다.
4. 검색어를 임베딩하고 현재 Embedding Profile·FAQ 버전에 맞는 질문 벡터를 HNSW로 검색합니다. 같은 FAQ의 여러 질문 표현은 FAQ 단위 후보로 모읍니다. 현재 검색에는 별도 리랭커를 적용하지 않습니다.
5. 원문 검색 경로에서 공식 FAQ 직접 반환 조건을 만족하면 등록 답변을 출력 코드 검사 후 반환합니다. 그 외에는 질문 유사도 기준을 적용하고, 일반 FAQ에는 답변 문장 검사도 적용합니다.
6. 남은 FAQ의 처리 의도에 따라 일반 근거, 본인 이용 정보, 상품 또는 매장 자료를 모읍니다. 모델에는 필요한 조회 도구만 제공합니다.
7. 모델 답변의 형식·근거·수치·위험 표현을 검사합니다. 여러 하위 질문 중 근거가 없는 부분은 답변을 보류하고, 검사 결과에 따라 일부 또는 전체 응답을 거절합니다.
8. 작업 결과와 관련 기록을 DB에 확정한 뒤 Redis 메시지를 ACK합니다. 화면은 작업 조회 결과를 받아 답변과 카드를 표시하고 폴링을 끝냅니다.

공식 답변 직접 반환도 임베딩과 검색을 사용합니다. 줄어드는 것은 답변 생성과 출력 AI 검사 호출이며, 사용자별·위치별 자료가 필요한 질문이나 분리·멀티턴 경로에는 이 단축 경로를 적용하지 않습니다.

## 화면과 서버가 주고받는 것

| 사용자 행동 | 요청 | 화면이 받을 결과 |
|---|---|---|
| 질문 전송 | POST `/chat/questions` | jobId, 서버가 발급한 idempotencyKey, 대화 정보, 작업 단계와 기한 |
| 답변 기다리기 | GET `/chat/jobs/{jobId}` | 처리 상태·단계, 성공 답변과 관련 자료 또는 실패 코드·재시도 가능 여부 |
| 답변 생성 중단 | POST `/chat/jobs/{jobId}/cancel` | 서버가 확정한 취소 상태 |
| 실패한 답변 재시도 | POST `/chat/questions/retries`, 기존 `Idempotency-Key` | 기존 질문의 재시도 작업 |
| 의도를 골라 다시 검색 | POST `/chat/questions/research`, 기존 `Idempotency-Key` | 선택한 의도로 제한한 새 검색 작업. 회원 전용 |

요청 필드와 응답 예시는 [프론트엔드 연동](../how-to/frontend-integration.md)에 있습니다. 최초 질문은 서버가 식별 키를 발급하므로 POST를 다시 보내는 것만으로 중복 접수를 막지 않습니다. 접수 응답을 받았다면 jobId로 조회하고, 허용된 재시도·재검색에 응답의 idempotencyKey를 사용합니다.

## 결과와 실패 처리

- 작업·답변 시도·성공한 질문과 FAQ 사용 기록을 저장합니다. 화면 표시용 자료도 작업 결과에 함께 보존합니다.
- 검색 근거 부족과 모델 자체의 답변 보류 중 수집 대상은 [미응답 개선](unanswered-improvement.md)으로 연결합니다. 시스템 장애나 가드가 만든 거절을 지식 부족과 같은 원인으로 다루지 않습니다.
- 입력 차단은 접수 전에 끝날 수 있고, 접수 후 실패는 작업 조회의 FAILED로 확인합니다. 202는 답변 완료를 뜻하지 않습니다.
- 모델 연결 실패는 정해진 기한 안에서 재시도합니다. 취소·시간 초과 후 늦게 도착한 결과는 저장하지 않습니다. 상세 경계는 [장애와 과부하 동작](../reliability-model.md)에 있습니다.

## 설계 근거와 관련 코드

질문 접수와 모델 실행을 나눠 HTTP 요청이 모델을 기다리지 않게 했습니다. DB에는 작업을 남기고 Redis에는 처리 신호를 보내므로, 신호가 유실돼도 저장된 작업을 기준으로 복구할 수 있습니다. 다만 복구 과정에서 모델 계산이 다시 실행될 수 있습니다.

- 접수와 처리: [ChatService](../../src/main/java/com/ubot/chat/service/ChatService.java), [ChatAnswerProcessor](../../src/main/java/com/ubot/chat/service/ChatAnswerProcessor.java)
- 검색과 자료 수집: [FaqRetrievalService](../../src/main/java/com/ubot/faq/retrieval/FaqRetrievalService.java), [ChatContextCollector](../../src/main/java/com/ubot/chat/context/ChatContextCollector.java), [AiService](../../src/main/java/com/ubot/ai/service/AiService.java)
- 회귀 테스트: [질문 분리](../../src/test/java/com/ubot/chat/service/ChatAnswerProcessorSubQuestionTest.java), [공식 FAQ 직접 반환](../../src/test/java/com/ubot/chat/service/ChatAnswerProcessorSemanticCacheTest.java), [이용 정보 카드](../../src/test/java/com/ubot/chat/controller/UserDataCardIntegrationTest.java)
- 검증 기록: [질문 분리·멀티턴](../질문분리_멀티턴_테스트_보고서.md), [HNSW 검색](../FAQ_HNSW_검색_테스트_보고서.md)
- 모델 연결·프롬프트·Profile 설정: [LLM 모듈 안내](../how-to/llm-module.md)
