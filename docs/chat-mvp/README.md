# 채팅 1차 MVP 구현 및 연동

이슈 #81의 상담 세션 생성/질문 입력/상태 조회/재시도 기능을 com.ubot.chat.mvp 패키지에 추가했습니다.
기존 API 명세의 경로와 기존 인증·FAQ 검색·임베딩·LLM 서비스를 사용합니다.
기존 팀원의 POST /chat/questions는 그대로입니다. 이번 프론트 연동은 아래 명세 경로를 사용합니다.
현재 질문만 FAQ 검색과 LLM에 전달하며 이전 대화 기억, 토큰 단위 출력은 포함하지 않습니다.
상담 세션은 질문들을 묶고 사용자 소유권을 확인하는 용도로만 사용합니다.

## 실행 전 확인 사항

1. DB 담당자가 schema-proposal.sql을 검토하고 새 마이그레이션으로 반영해야 합니다.
   현재 로컬 소스의 마이그레이션에는 chat_sessions, answer_attempts_history,
   question_log의 session_id/llm_question이 아직 없습니다.
   SQL은 검토 파일로만 작성했으며 DB에 실행하지 않았습니다. 기존 Flyway 파일도 수정하지 않았습니다.
   세션 테이블 및 질문의 session_id, 시도의 question_log_id, PENDING 상태,
   중복 방지 제약은 DB 담당자와 조율해야 하는 제안이며 실제 DB에 적용하지 않았습니다.
2. 제공된 FAQ 상담 지침과 추가 규칙을 prompts/faq-system.txt에 반영했습니다.
   faq-user.txt는 {{question}}, {{faqs}}에 현재 질문과 검색된 FAQ 원문을 넣습니다.
   프롬프트는 JSON 객체(status, answer, evidence_ids)를 요구하며, 기존 응답 코드는
   모델 출력 전체를 answer 문자열로 전달합니다. JSON 필드 파싱과 검증은 후속 작업입니다.
   프롬프트 파일이 비어 있거나 누락된 경우 CHAT_PROMPT_NOT_READY 실패로 반환합니다.

## HTTP API

모든 API는 기존 Spring Security의 로그인 필수 정책을 적용받습니다.
Authorization: Bearer <JWT>를 사용하며 userId는 요청 본문으로 받지 않습니다.

아래 요청/응답 필드는 프론트 연동안입니다. 팀 상세 명세에 본문/상태 코드가 별도로
정해져 있다면 그 계약에 맞춰 조율합니다. SSE로 상태와 완성된 답변을 전달합니다.

- POST /chat/sessions
  - Body 없음. 인증된 사용자 ID로 상담 세션을 생성합니다.
  - 응답: HTTP 201, ApiResponse<ChatSessionResponse>
  - data: {"sessionId":20,"createdAt":"2026-09-23T10:00:00"}
- POST /chat/sessions/{sessionId}/questions
  - Header: Accept: text/event-stream, Content-Type: application/json. 최초 질문에는 멱등키를 보내지 않습니다.
  - Body: {"question":"유심 교체 준비물이 무엇인가요?"}
  - 응답: SSE processing 이벤트 후 completed 또는 failed 이벤트. 완성된 답변은 한 번에 전달합니다.
- POST /chat/sessions/{sessionId}/questions/{questionId}/retries
  - Header: Idempotency-Key: <실패 응답에서 받은 idempotencyKey 그대로>, Accept: text/event-stream
  - Body 없음. 원본 질문을 DB에서 읽어 그대로 재실행합니다.
  - 본인 질문만 재시도할 수 있으며 질문 내용을 재시도 요청으로 바꿀 수 없습니다.
- GET /chat/sessions/{sessionId}/questions/{questionId}
  - 해당 세션에 속한 본인 질문의 최신 시도 상태를 ApiResponse<ChatAttemptResponse>로 조회합니다.
  - 다른 사용자 세션 또는 경로의 세션과 질문이 일치하지 않으면 404 CHAT-102입니다.

POST와 Authorization 헤더를 사용하므로 프론트는 fetch의 응답 스트림으로 SSE를 읽습니다.
브라우저 EventSource는 이 POST 요청을 직접 보낼 수 없습니다.
네트워크 청크는 이벤트 단위가 아니므로 UTF-8 증분 디코딩 후 빈 줄 기준으로 SSE를 파싱해야 합니다.
서버가 최초 질문 저장 시 사용자 ID·질문·생성시간으로 멱등키를 만들어 DB에 저장합니다.
실패 응답의 idempotencyKey를 재시도 헤더에 그대로 보내며 프론트에서 새 키를 만들지 않습니다.
해당 질문의 원래 키와 다른 키를 보내면 409 CHAT-103입니다.
이미 생성 중인 질문에 같은 키로 재시도를 보내면 processing 이벤트와 기존 식별자를 반환하고
그 중복 연결을 종료합니다. 새 작업을 시작하거나 시도 횟수를 늘리지 않습니다.
이미 성공한 질문이면 저장된 completed 이벤트를 반환합니다.
실패 상태의 질문에 같은 키로 재시도하면 다음 시도를 실행합니다. 최대 실행 횟수는 총 3회입니다.
프론트는 전송 중 재시도 버튼을 비활성화하고, 네트워크 오류 시 POST를 자동 재전송하지 말고
질문 처리 상태 GET을 확인합니다. 같은 키만으로는 실패 후 도착한 지연 재전송과 새 클릭을
구분할 수 없으므로, 실패 후 다시 받은 재시도 요청은 다음 시도로 처리합니다.
최초 질문 POST는 새 질문을 생성합니다. 그 응답에서 받은 questionId로 이후 조회/재시도합니다.

## 이벤트 데이터

processing/completed/failed 모두 다음 필드를 포함합니다.

- sessionId, questionId, attemptId: 상담 세션, 질문 및 해당 생성 시도 식별자
- idempotencyKey: 서버가 만든 64자리 키. 실패 응답에 포함하며 재시도 때 그대로 전달
- status: PENDING / SUCCESS / FAIL (저장 결과 확인 불가 시 UNKNOWN)
- attemptCount, maxAttempts=3, remainingAttempts
- success, retryable
- answer: 성공한 완성 답변, 그 외 null
- errorCode, message

재시도 버튼은 failed 이벤트의 retryable=true일 때만 표시합니다.
첫 실행이 1회, 첫 재시도가 2회, 두 번째 재시도가 3회입니다.
3번째 실패 후에는 retryable=false이며 서버도 4번째 실행을 거절합니다.
FAQ 없음/근거 부족, 프롬프트 미준비, 잘못된 모델 설정은 즉시 재시도 대상으로 삼지 않습니다.
SSE 전송 시작 전 검증/인증/한도 오류는 기존 ApiResponse JSON과 HTTP 4xx/5xx입니다.
SSE가 시작된 뒤 생성 오류는 failed 이벤트로 전달합니다.
UNKNOWN은 DB 커밋 결과를 확정하지 못한 상태이므로 재시도 대신 결과 조회를 먼저 합니다.

## 저장과 동시성

ChatMvpSessionRepository는 세션 생성 시 사용자에게 소속된 세션 한 행을 저장합니다.
QuestionLogRepository는 최초 질문 요청에 세션 ID와 질문 한 행을 저장합니다.
각 생성 시도마다 answer_attempts_history에 새 행을 저장하고 attempt_count를 1~3으로 증가시킵니다.
idempotency_key는 인증된 사용자 ID·저장한 질문·최초 질문의 DB 생성시간을 조합한 SHA-256입니다.
입력 표현은 userId + ":" + 질문의 Java 문자열 길이 + ":" + 질문 + ":" + createdAt입니다.
생성시간은 question_log.created_at에 저장된 값이며 재시도 요청 시간으로 다시 계산하지 않습니다.
처음 생성한 동일 키를 모든 시도 행에 저장합니다. 시도마다 created_at은 따로 기록합니다.
서버가 사용자 ID를 인증 정보에서 가져오며 질문 생성/조회/재시도에서 세션 소유권을 검사합니다.
질문 조회/재시도에는 세션과 질문이 실제로 연결되어 있는지도 검사합니다.
질문 행 잠금과 DB unique 제약으로 동일 질문에 대한 재시도 처리를 직렬화합니다.
최초 키는 질문마다 고유하고, (idempotency_key, attempt_count) 조합도 고유합니다.
키 단독 UNIQUE 제약은 재시도 행의 같은 키 저장을 막으므로 사용하지 않습니다.

기존 FaqVectorService -> EmbeddingService/벡터 DB, AiService -> PromptService/LlmService를 호출합니다.
성공 시 완성 답변은 question_log.llm_question에 저장하며, 검색 FAQ는 기존 FaqLogRepository로 저장합니다.
이 작업과 SUCCESS 상태 전환은 하나의 짧은 트랜잭션입니다.
실패 시 FAIL, 오류 코드/안내 메시지를 저장합니다. 원본 내부 예외 메시지는 사용자에게 노출하지 않습니다.
임베딩/LLM 호출 중에는 DB 트랜잭션과 행 잠금을 잡고 있지 않습니다.
DB에 저장되지 않은 성공을 completed로 알리지 않습니다.
만료된 PENDING은 조회/재시도 시 DB 시각으로 FAIL 처리하여 서버 재시작 뒤에도 복구합니다.
타임아웃 후 뒤늦게 도착한 응답은 기존 실패나 새 재시도의 결과를 덮어쓰지 않습니다.
답변 매장 연결(answer_stores)은 이번 FAQ 답변 경로에서 쓰지 않으며 팀원의 매장 API는 그대로입니다.

## 실행 설정

새 패키지만 사용하는 설정이며 기존 설정 파일을 수정하지 않았습니다.

- chat.mvp.timeout: 150s (대기열 포함 전체 생성 제한)
- chat.mvp.workers: 4
- chat.mvp.queue-capacity: 16
- chat.mvp.top-k: 3
- chat.mvp.confidence-threshold: 0.75

LLM/Ollama 모델 설정은 기존 설정을 그대로 사용합니다.
별도의 제한된 작업 스레드와 타이머를 사용하며 공용 MVC executor를 변경하지 않습니다.
15초 간격 SSE 주석으로 연결을 유지하고, 브라우저 연결이 끊겨도 결과 저장은 계속합니다.
DB 장애 중에는 로그 저장을 보장할 수 없으며 결과를 UNKNOWN으로 안내합니다.
중단 요청을 외부 LLM 서버가 무시하면 해당 호출의 물리적 종료까지 보장할 수는 없지만,
그 결과는 완료 상태에 반영하지 않습니다.

## 보존 범위와 검증

기존 인증, FAQ, 임베딩, 프롬프트 서비스, LLM, 매장, 공통 예외, 채팅 Java 코드 및 테스트를 수정하지 않았습니다.
기존 파일의 변경은 FAQ 프롬프트 리소스 두 개이며, 나머지는 채팅 MVP 코드·테스트·문서 추가입니다.
검증 코드는 질문/FAQ 전달, 오류 처리, 최초 포함 3회 제한, 소유권, 중복 요청, 늦은 결과 처리,
명세의 HTTP 경로 및 다른 세션의 질문 조회/재시도 차단을 포함합니다.
멱등키 생성 재료, 실패 이벤트의 키 반환, 동일 키 재시도 및 잘못된 키 거절도 검증합니다.
채팅 MVP 테스트 48개는 저장소와 외부 서비스의 mock을 사용합니다.
기존 DB 통합 테스트는 Testcontainers의 임시 DB를 사용합니다. 전체 테스트가 통과하더라도
신규 채팅 스키마의 실제 저장 검증이나 Ollama의 답변 품질 검증을 대신하지 않습니다.
검증 명령은 ./gradlew test build이며, Java 21과 실행 중인 Docker가 필요합니다.
