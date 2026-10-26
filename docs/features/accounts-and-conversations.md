# 회원·비회원 이용과 대화 기록

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [상담사 인계](counselor-handoff.md)

비회원도 제한된 횟수 안에서 AI 상담을 이용할 수 있습니다. 로그인한 고객은 본인의 대화를 이어 보고, 개인 이용 정보 조회·예약·상담사 연결을 사용할 수 있습니다.

## 비회원에서 회원으로 이어가기

1. 비회원이 질문하면 서버가 세션 쿠키와 게스트 대화를 연결합니다. 브라우저는 이후 요청에 같은 쿠키를 보내야 합니다.
2. 서버가 관리자 설정에 따른 질문 한도를 검사합니다. 게스트 세션은 회원 인증 수단이 아니며, Spring Security의 회원 인증은 JWT로 유지합니다.
3. 같은 세션에서 로그인한 뒤 승계를 수행하는 채팅 요청·작업 조회 등을 호출하면, 서버가 아직 회원에게 연결되지 않은 게스트 대화를 본인 계정에 귀속시킵니다.
4. 대화와 관련 답변 시도·질문 기록의 사용자 연결을 함께 바꾸고 게스트 세션을 정리합니다. 이미 다른 회원에게 귀속되었거나 삭제된 대화는 다시 승계하지 않습니다.
5. 이후에는 대화의 공개 UUID와 로그인 계정으로 소유권을 확인합니다.

세션이 만료되거나 API 메모리의 세션을 잃으면 과거 게스트 대화를 같은 방식으로 증명할 수 없습니다. 브라우저에 내용이 남아 있다는 사실만으로 서버 대화의 소유권을 부여하지 않습니다.

## 인증과 개인 정보

회원가입·로그인·로그아웃·토큰 재발급과 내 정보 조회·수정을 제공합니다. 로그인은 access token과 refresh token을 발급하고, refresh token은 회전시키며 DB에는 해시로 저장합니다. 보호 API는 토큰뿐 아니라 현재 DB의 사용자 상태와 역할을 확인합니다.

토큰 만료 때만 유효한 refresh token으로 재발급을 시도합니다. 다른 사람의 리소스, 회수된 권한, 재사용·폐기된 토큰을 자동 반복 요청으로 해결하지 않습니다. 구체적인 토큰 수명·오류·CORS는 [프론트엔드 인증 연동](../how-to/frontend-integration.md#인증)에 있습니다.

## 대화를 다시 열기

| 사용자 행동 | API | 저장·반환 내용 |
|---|---|---|
| 이전 대화 목록 보기 | GET `/conversations/me` | 본인 대화, 미리보기, 상담 이력 여부 |
| AI 문답 다시 보기 | GET `/conversations/{conversationId}/messages` | 저장된 문답과 FAQ 선택 저장본 |
| FAQ 선택하기 | POST `/conversations/faq-selections` | 선택 시점의 질문·답변 사본과 대화 연결 |
| AI·상담 전체 흐름 보기 | GET `/conversations/{conversationId}/timeline` | 문답·FAQ 선택·상담 시작과 종료·허용 메시지, 다음 커서 |
| 대화 삭제 | DELETE `/conversations/{conversationId}` | 삭제 가능한 본인 대화의 논리 삭제 |

FAQ 선택은 selectionId로 중복 저장을 막고, 나중에 FAQ가 바뀌어도 당시 내용을 유지합니다. 새로고침·재로그인 후 서버 기록을 읽어 복원하며, 상담 인계도 이 저장본을 사용합니다.

현재 삭제 API는 진행 중인 답변이 있거나 상담 이력이 있는 대화를 거절합니다. 화면에서 숨겼다는 이유로 서버 기록이 삭제되었다고 간주하면 안 됩니다. 보관 기간에 따른 정리는 별도의 데이터 보관 정책을 따릅니다.

## 관련 코드와 검증

- 회원 인증: [AuthService](../../src/main/java/com/ubot/auth/service/AuthService.java), [RefreshTokenService](../../src/main/java/com/ubot/auth/service/RefreshTokenService.java)
- 게스트 승계: [GuestConversationService](../../src/main/java/com/ubot/guest/session/GuestConversationService.java)
- 대화·FAQ 저장: [ConversationService](../../src/main/java/com/ubot/conversation/service/ConversationService.java), [ConversationFaqSelectionService](../../src/main/java/com/ubot/conversation/service/ConversationFaqSelectionService.java)
- 회귀 테스트: [인증](../../src/test/java/com/ubot/auth/AuthIntegrationTest.java), [대화](../../src/test/java/com/ubot/conversation/ConversationIntegrationTest.java), [삭제 동시성](../../src/test/java/com/ubot/conversation/ConversationDeletionConcurrencyTest.java), [상담 이력](../../src/test/java/com/ubot/counsel/CounselIntegrationTest.java)
