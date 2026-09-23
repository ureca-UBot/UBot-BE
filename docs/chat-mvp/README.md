# 채팅 API

기존 ChatController와 ChatService에서 현재 질문 한 개에 대한 답변을 생성합니다.
세션과 이전 대화 기억 기능은 사용하지 않습니다.

## 기존 코드 재사용

- FaqVectorService → EmbeddingService/FaqVectorRepository: 질문 임베딩과 FAQ 검색
- AiService → PromptService → LlmService: 기존 프롬프트와 LLM 호출
- FaqLog/FaqLogRepository: 기존 JPA 엔티티·저장소
- CustomUserDetails, SecurityConfig, ChatException, GlobalExceptionHandler: 기존 인증·예외 처리
- 기존 검색 기준 TOP_K=3, 유사도 0.75 유지

별도 chat/mvp 컨트롤러, 파이프라인, JDBC 저장소는 제거했습니다.
AnswerAttemptsHistory, QuestionLog와 각 JpaRepository로 기록을 저장합니다.
기존 ChatController, ChatService, 요청·응답 DTO, ChatErrorCode는 필요한 기능을 추가합니다.

## 파일 구조와 역할

```text
com.ubot
├── chat
│   ├── controller/ChatController.java
│   ├── dto
│   │   ├── request/ChatRequestDto.java
│   │   └── response/ChatResponseDto.java
│   ├── entity
│   │   ├── AnswerAttemptsHistory.java
│   │   └── QuestionLog.java
│   ├── repository
│   │   ├── AnswerAttemptsHistoryRepository.java
│   │   └── QuestionLogRepository.java
│   ├── service
│   │   ├── ChatService.java
│   │   └── ChatHistoryService.java
│   └── exception
│       ├── ChatException.java
│       └── ChatErrorCode.java
└── global
    └── config/AsyncConfig.java
```

- ChatController: 로그인 사용자 확인과 질문·재시도 HTTP 요청 처리
- ChatService: 기존 FAQ·AI 서비스 호출, 비동기 답변 처리와 SSE 상태 전달
- ChatHistoryService: 멱등키·시도 횟수 관리, 질문·답변·FAQ·시도 기록의 JPA 저장 트랜잭션
- AsyncConfig: 작업 스레드 설정. 공통 설정 위치를 global/config로 통일

엔티티의 @Column에는 name만 지정합니다. 길이·NOT NULL·유일 제약은 DB 담당자가 관리합니다.
DTO는 record와 RequestDto/ResponseDto 이름을 사용하며, 저장소는 엔티티 이름 + Repository로 작성합니다.
이번에 작성한 코드의 들여쓰기는 4칸 크기로 표시할 탭을 사용합니다.
기존 팀원의 common/auth 패키지는 이번 정리에서 이전하지 않습니다.

## API

| 메서드 | 경로 | 요청 |
|---|---|---|
| POST | /chat/questions | 로그인 인증 + JSON question |
| POST | /chat/questions/retries | 로그인 인증 + Idempotency-Key 헤더 |

응답은 text/event-stream입니다. 이전 /chat/questions의 단일 JSON 응답 대신 SSE를 사용하므로 프론트 연동도 맞춰야 합니다.
질문자 ID는 인증 정보에서 얻으며 클라이언트가 보낸 userId를 신뢰하지 않습니다.
세션 생성·상태 조회 API는 없습니다.

## 처리 순서

1. 로그인 사용자와 질문을 확인합니다.
2. 사용자 ID·질문·최초 생성시간을 SHA-256으로 변환하고 PENDING 시도 기록을 JPA로 저장합니다.
3. processing 이벤트를 전송하고 별도 작업 스레드에서 기존 검색·LLM 서비스를 호출합니다.
4. 성공하면 QuestionLog에 질문과 답변, FaqLog에 참고 FAQ를 저장하고 시도를 SUCCESS로 변경합니다. 이 트랜잭션이 완료된 뒤 completed를 보냅니다.
5. LlmException·EmbeddingException 등을 서비스에서 처리하고 별도 트랜잭션으로 FAIL 기록을 남긴 뒤 failed를 보냅니다.

이벤트 data는 ChatResponseDto(answer, success, status, idempotencyKey, attemptCount, retryable)입니다.
status는 PENDING/SUCCESS/FAIL이며 이벤트 이름은 processing/completed/failed입니다.
답변은 완성된 문자열 한 번으로 보내며 토큰 스트리밍은 하지 않습니다.

## 재시도

- 최초 시도 1회 + 재시도 2회 = 총 3회입니다.
- 벡터 검색 DB 오류, EmbeddingException 또는 LlmException으로 실패한 경우만 재시도를 허용합니다.
- FAQ 검색 결과 없음·유사도 부족·프롬프트 오류·채팅 기록 저장 오류 등은 retryable=false이며 재시도 API에서도 거절합니다.
- 실패 응답의 멱등키를 그대로 재사용합니다. 실패 시도는 덮어쓰지 않고 새 행에 2·3회차를 저장합니다.
- 같은 사용자·키의 최초 행을 JPA 비관적 잠금으로 잠근 뒤 상태와 횟수를 확인합니다.
- 처리 중, 이미 성공, 총 3회 소진, 타인의 키는 거절합니다.
- 새 질문마다 서버가 새 키를 발급하므로 네트워크 오류 시 최초 POST를 자동 재전송하지 않습니다.
- 재시도 버튼은 처리 중 비활성화하고 failed 이벤트의 retryable을 확인합니다.
- 서버 재시작 등으로 남은 PENDING 기록의 복구는 이 MVP에 포함하지 않습니다. SSE 연결 종료만으로 실행 중인 모델 작업을 취소하지 않습니다.

## DB 담당자 확인

schema-proposal.sql은 검토용이며 개발 DB에 자동 적용하지 않습니다.
answer_attempts_history.user_id(소유권), PENDING 상태, (idempotency_key, attempt_count) 유일 제약과 question_log.llm_question 컬럼이 필요합니다.
JPA는 ddl-auto=none을 유지합니다. 담당자의 스키마 반영 전 실제 채팅 기록 저장은 실행할 수 없습니다.
테스트 전용 컨테이너에서는 테스트 SQL로 이 구조를 검증할 수 있습니다.

## 프론트 및 LLM 연동

프론트의 상태 문구·재시도 버튼 구현은 별도입니다.
제공받은 faq-system.txt는 수정하지 않습니다.
현재 LLM 서비스는 모델이 반환한 JSON 문자열을 그대로 answer에 담습니다. status/answer/evidence_ids 분리 처리는 별도 작업입니다.
