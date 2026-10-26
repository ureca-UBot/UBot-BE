# 질문과 답변 보호

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [상담 메시지 보호](counselor-handoff.md)

고객의 질문과 생성 답변이 상담 범위를 벗어나거나 위험한 표현·확인되지 않은 정보를 전달하지 않도록 단계별로 검사합니다. 관리자는 검사 기록을 검토하고 차단 정책 후보를 검증한 뒤 반영합니다.

## 검사 위치와 결과

| 단계 | 입력 | 검사와 결과 |
|---|---|---|
| 질문 접수·처리 준비 | 고객 질문 | 인사·이모지 정리, 금지어·프롬프트 주입 검사. 위험 요청이 섞이면 질문 전체 거절 |
| 질문 정리 | 질문과 필요한 이전 대화 | 질문 분리·멀티턴 또는 검색 부족으로 판정이 필요한 경로에서 안전 판정과 검색어 정리를 함께 수행 |
| 답변 코드 검사 | 모델 출력과 실제 제공 근거 | 형식·근거·수치·정책 위반 확인. 판정에 따라 하위 답변 보류 또는 전체 거절 |
| 출력 AI 검사 | 코드 검사 후 합친 답변 | ALLOW·BLOCK·REVIEW 판정. 생성 답변을 다시 쓰지는 않음 |
| 공식 FAQ 직접 반환 | 관리자가 등록한 공식 답변 | 출력 코드 검사 적용. 답변 생성과 출력 AI 검사는 생략 |

모든 질문이 입력 AI 검사를 거치는 것은 아닙니다. 원문 검색만으로 충분한 경로에서는 입력 AI를 호출하지 않습니다. 질문 정리를 끈 경우에도 별도 입력 AI 호출을 추가하지 않습니다.

## 거절·보류·검사 실패

- 입력 BLOCK은 CHAT-022, 출력 BLOCK은 LLM-006으로 처리합니다. 위험한 요청이나 답변을 부분적으로 숨겨 성공 응답으로 바꾸지 않습니다.
- 통합 AI의 REVIEW는 LLM-008 보류 오류입니다. 허용으로 간주하지 않으며, 그 판정만으로 관리자 분석 작업을 자동 등록한다는 뜻도 아닙니다.
- 활성 안전 판정의 잘못된 JSON·알 수 없는 판정·연결 오류는 정상 통과로 대체하지 않습니다. 일반 분류 전용 경로의 원문 검색 복구와 구분합니다.
- 코드 검사에서 일부 하위 답변을 보류한 경우와 모델이 스스로 답을 보류한 경우를 구분해 저장합니다. 가드가 만든 보류를 FAQ 부족으로 자동 수집하지 않습니다.
- 입력·출력 가드는 각각 코드 검사와 AI 판정 스위치를 가집니다. 상위 스위치를 끄면 코드 검사도 꺼지므로 [설정과 호출 순서](../how-to/ai-guard-integration.md)를 확인해야 합니다.

## 관리자가 안전 정책을 개선하는 흐름

1. 검사 기록과 가드 적용 전 응답에서 검토할 대상을 수집합니다.
2. 관리자가 수동 분석을 요청하거나 자동 분석 설정을 켜면 Worker가 원문 스냅샷을 분석합니다.
3. 모델이 제안한 금지어 후보·위험 문장을 서버가 다시 검증합니다. 모델 제안만으로 운영 금지어를 바꾸지 않습니다.
4. 관리자가 후보를 수정하고 기준 사례로 오차단·회귀 검사를 실행합니다.
5. 검사한 후보·정책·자료 버전이 현재와 같을 때 승인할 수 있습니다. 승인한 금지어는 기존 금지어 관리 기능을 통해 적용합니다.
6. 위험 문장은 관리자 경고로 전달합니다. 경고 읽음 처리와 차단 정책 승인은 별개입니다.

금지어 관리는 `/admin/forbidden-words`, 후보 분석·검증·승인은 `/admin/guard-review`에서 제공합니다. 저장 대상·보관 기간·재평가·승인 조건은 [관리자 위험 표현 검토](../how-to/guard-review.md)에 있습니다. 상담의 금지 문장 의미 검사는 [상담사 연결](counselor-handoff.md)에서 별도로 설명합니다.

## 관련 코드와 검증

- 입력 검사: [ChatInputGuardService](../../src/main/java/com/ubot/chat/service/ChatInputGuardService.java), [AiInputGuardService](../../src/main/java/com/ubot/ai/service/AiInputGuardService.java)
- 출력 검사: [AiOutputGuardService](../../src/main/java/com/ubot/ai/service/AiOutputGuardService.java), [AiLlmOutputGuardService](../../src/main/java/com/ubot/ai/service/AiLlmOutputGuardService.java)
- 관리자 검토: [AdminReviewController](../../src/main/java/com/ubot/guardreview/controller/AdminReviewController.java), [ReviewValidationService](../../src/main/java/com/ubot/guardreview/service/ReviewValidationService.java)
- 회귀 테스트: [입력 AI 판정](../../src/test/java/com/ubot/ai/service/AiInputGuardServiceTest.java), [출력 전체 거절](../../src/test/java/com/ubot/ai/service/AiOutputGuardWholeRejectionTest.java), [승인 검증](../../src/test/java/com/ubot/guardreview/service/ReviewValidationServiceTest.java)
- 실험 기록: [출력 가드 분석](../output-guard-analysis.md), [출력 LLM 가드 비교](../pr291-output-llm-guard-comparison.md)

테스트는 검사 경로와 정책 회귀를 확인합니다. 모든 새로운 표현의 미탐·오차단을 없앤다는 의미는 아니며, 실제 모델 평가와 코드 검사 테스트의 범위를 구분해야 합니다.
