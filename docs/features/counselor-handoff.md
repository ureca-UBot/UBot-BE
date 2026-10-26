# 상담사 인계와 메시지 보호

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [회원과 대화 기록](accounts-and-conversations.md)

로그인한 고객이 AI 대화에서 상담사를 호출하면 상담사가 같은 대화의 이전 내용을 보고 이어서 상담합니다. 고객이 설명을 반복하지 않도록 서버에 저장한 기록을 인계하고, 안전 검사를 통과한 상담 메시지만 상대방에게 전달합니다.

## 연결부터 재상담까지

1. 고객 화면이 현재 대화 ID와 handoffId로 상담을 요청합니다. 비회원은 상담을 요청할 수 없습니다. 같은 요청을 다시 보내면 기존 접수 결과를 반환합니다.
2. 서버가 대화 소유권을 확인하고 AI 문답, 선택 시점의 FAQ 저장본, 허용된 이전 상담 메시지를 읽어 인계 사본을 만듭니다. 화면에서 만든 답변 본문은 인계 근거로 사용하지 않습니다.
3. 상담사 가용 상태와 대기 순서에 따라 배정합니다. 상담사는 heartbeat로 접속 상태를 알리고 배정받은 상담의 이력과 메시지를 조회합니다.
4. 고객과 상담사가 메시지를 보내면 서버가 PENDING으로 접수합니다. 기존 금지어 필터를 먼저 적용하고, 통과한 메시지만 임베딩해 활성 금지 문장과 의미 유사도를 비교합니다.
5. ALLOWED만 상대방에게 전달합니다. BLOCKED와 CHECK_FAILED는 전달하지 않으며, 검사 중에는 세션 잠금과 DB 트랜잭션을 계속 점유하지 않습니다.
6. 상담을 종료하면 기존 대화에서 AI 질문과 FAQ 선택을 이어갈 수 있습니다. 대화 목록·통합 타임라인에는 허용된 상담 기록도 함께 남습니다.
7. 재상담할 때는 그 시점의 서버 대화를 다시 읽으므로 이전 상담 종료 후 추가한 AI 문답과 저장된 FAQ 선택도 인계합니다.

## 화면과 서버의 연결

| 화면 행동 | API | 결과 |
|---|---|---|
| 상담사 호출 | POST `/counsel/requests` | 대기 또는 배정 상태의 상담 |
| 상담사 온라인·접속 유지 | `/counsel/counselors/availability`, `/heartbeat` | 가용 상태와 접속 갱신 |
| 인계 이력 읽기 | GET `/counsel/sessions/{id}/context` | 서버 저장 문답·FAQ 사본·이전 상담 |
| 메시지 전송·수신 | `/counsel/sessions/{id}/messages` | 멱등 접수 결과, 전달 순서, 조회자 기준 mine |
| 상담 종료 | POST `/counsel/sessions/{id}/end` | 종료 또는 대기 취소 |
| 재로그인 후 이력 확인 | GET `/conversations/{conversationId}/timeline` | items, nextCursor, hasNext |

전체 요청 형식·권한·설정은 [상담 API와 실행 안내](../how-to/counsel.md)에 있습니다. 실시간 메시지의 전달 순서와 통합 타임라인의 커서는 서로 다른 용도입니다.

## 연결이 끊기거나 검사가 실패하면

- 상담사 heartbeat가 끊기면 일시 중지 후 재배정할 수 있습니다. 다시 대기하는 고객은 원래 대기 시작 시각을 유지합니다.
- 재배정된 뒤 이전 상담사는 그 상담을 읽을 수 없습니다. 종료 후에는 고객과 마지막 담당 상담사의 접근 권한을 확인합니다.
- 검사 용량 대기와 Worker 응답은 하나의 응답 기한을 공유합니다. 검사를 완료하지 못하면 CHECK_FAILED로 확정하며, 직접 모델 호출로 우회하지 않습니다.
- 같은 clientMessageId는 같은 접수 결과를 반환합니다. 확정된 검사 실패 메시지를 새로 검사하려면 새 ID로 전송해야 합니다.
- 목록 미리보기·통합 이력·후속 상담 인계에는 차단·검사 실패 원문을 포함하지 않습니다.

## 관리자가 차단 기준을 바꾸는 흐름

단어 차단은 기존 금지어 관리와 공유합니다. 의미 검사용 금지 문장은 `/admin/counsel/safety/sentences`에서 관리하며 등록·내용 변경·재활성화 시 자동으로 색인합니다. 심각도만 바꿀 때는 기존 벡터를 유지합니다. 수정 버전이 달라진 요청이나 늦게 도착한 옛 색인 결과는 반영하지 않습니다.

유사도 기반 차단은 인용·정상 불만도 비슷한 표현이면 막을 수 있습니다. 관리자는 원문 없는 판정 집계와 평가 API로 정책을 확인해야 합니다. 상담 안전 문장과 [AI 답변 가드 후보](ai-safety.md)는 적용 대상과 승인 절차가 다릅니다.

## 관련 코드와 검증

- 인계·배정: [SessionService](../../src/main/java/com/ubot/counsel/session/service/SessionService.java), [HandoffHistoryService](../../src/main/java/com/ubot/counsel/session/service/HandoffHistoryService.java)
- 메시지 보호: [MessageService](../../src/main/java/com/ubot/counsel/message/service/MessageService.java), [SafetyGuardService](../../src/main/java/com/ubot/counsel/safety/service/SafetyGuardService.java)
- 이력 조회: [ConversationTimelineService](../../src/main/java/com/ubot/counsel/history/service/ConversationTimelineService.java)
- 회귀 테스트: [CounselIntegrationTest](../../src/test/java/com/ubot/counsel/CounselIntegrationTest.java)
- 초기 색인과 보관 기간: [상담 API와 실행 안내](../how-to/counsel.md)
