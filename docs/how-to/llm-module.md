# LLM 모듈 연결 안내

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

## 구현 범위

채팅에서 검색한 FAQ를 프롬프트에 넣어 Ollama를 호출하고, 생성된 최종 답변을 채팅으로 반환합니다.
검색은 `ChatAnswerProcessor`가 담당하고, `AiService`가 프롬프트 구성과 LLM 호출을 연결합니다.

```text
POST /chat/questions
  → ChatService: 금지어 검사, 답변 시도 기록 생성
  → ChatAnswerExecutor: 가상 스레드에 답변 생성 작업 제출
  → ChatAnswerProcessor: FAQ 검색 및 각 결과의 유사도 필터링, intent별 답변 자료 수집
  → AiService.generateAnswer(AnswerMaterials)
  → PromptService.createPrompt(question, faqs, sections)
  → LlmService.generateAnswer(LlmRequestDto)
  → LlmClient / OllamaClient
  → Spring AI OllamaChatModel
  → Ollama
  → LlmResponseDto(answer)
  → ChatResponseDto: 채팅 응답
```

기본 설정으로 TOP-K 3개를 검색한 뒤, 각 FAQ의 유사도를 기준값 0.75와 비교합니다.
기준값 이상인 FAQ만 원래 질문과 함께 전달하고, 실제 전달한 FAQ만 참고 로그에 저장합니다.
기준값 미만이거나 유한한 숫자가 아닌 점수는 제외하며, 남은 FAQ가 없으면 LLM을 호출하지 않고
`INSUFFICIENT_FAQ`로 실패 처리합니다. 검색 결과 자체가 없으면 기존 `NO_FAQ`를 반환합니다.
검색 개수와 기준값은 `CHAT_TOP_K`, `CHAT_CONFIDENCE_THRESHOLD`로 설정하며, FAQ를 다시 조회하지 않습니다.

프롬프트는 `src/main/resources/prompts/faq-system.txt`(시스템 지침)와
`faq-user.txt`(사용자 메시지 템플릿)로 분리되어 있고, 두 파일 모두 본문이 들어 있습니다.
파일이 없거나 비어 있거나 사용자 템플릿에 `{{question}}`과 `{{faqs}}` 중 하나라도 빠지면
LLM을 호출하지 않고 `CHAT-014`(`답변 프롬프트가 준비되지 않았습니다.`)로 실패합니다.
작성 방법은 [프롬프트 안내](../../src/main/resources/prompts/README.md)를 참고하세요.

### 출력 형식과 서버 처리 (JSON 분리는 후속 작업)

현재 시스템 지침은 모델에게 아래 형식의 **JSON 객체 하나**만 출력하도록 요구합니다.
이 JSON의 `status`·`answer`·`evidence_ids`를 분리하는 처리는 이슈 #81에서 후속 작업으로 정해 두었습니다.

| 키 | 값 |
|---|---|
| `status` | `ANSWER`, `PARTIAL`, `CLARIFY`, `ABSTAIN`, `CONFLICT`, `OUT_OF_SCOPE` 중 하나 |
| `answer` | 한국어 답변 문자열 |
| `evidence_ids` | 실제로 사용한 FAQ ID 문자열 배열 |

그러나 서버(`OllamaClient` → `ChatAnswerProcessor`)는 이 JSON을 파싱하지 않습니다. 모델이 반환한 문자열 전체를
`LlmResponseDto.answer`로 받아 `question_log.llm_question`에 저장하고, 채팅 응답의 `answer`로 그대로 반환합니다.
따라서 클라이언트에는 JSON 문자열이 그대로 전달되고, 모델이 `ABSTAIN`·`OUT_OF_SCOPE` 등으로 판단해도
서버 기준 상태는 `SUCCESS`입니다. `evidence_ids`와 관계없이 `faq_log`에는 모델에 전달한 FAQ 전체가 기록됩니다.
Ollama의 `format`(JSON 모드) 옵션도 지정하지 않으므로 모델이 JSON 형식을 지키는지는 보장되지 않습니다.
실제로 `exaone3.5:7.8b`로 확인했을 때(2026-09-30) 모델이 JSON을 마크다운 코드 블록(```` ```json … ``` ````)으로 감싸 반환했고,
서버는 이 문자열을 그대로 `answer`에 넣었습니다.
현재는 UBot-FE가 화면에 표시할 때 이 문자열을 JSON으로 파싱해 안쪽 `answer`만 보여 주지만,
코드 블록으로 감싸져 있으면 파싱에 실패해 코드 블록이 그대로 보입니다.
#81을 구현해 응답 형식을 바꿀 때는 프론트와 함께 맞춰야 합니다([프론트엔드 연동 가이드](frontend-integration.md#알아-둘-점)).

## 입력과 출력

- `LlmRequestDto.messages`: 순서가 있는 `List<LlmMessageRequestDto>`.
- `LlmMessageRequestDto`: `role`과 `content`를 갖는 record.
- 역할은 `SYSTEM`, `USER`, `ASSISTANT`. 마지막 메시지는 현재 질문을 담은 `USER`여야 합니다.
- 메시지 목록·역할·본문이 없거나 본문이 공백이면 호출 전에 `LLM_REQUEST_INVALID`가 발생합니다.
- `LlmResponseDto.answer`: 완성된 답변 문자열. Ollama의 별도 `thinking` 필드는 반환하지 않습니다.

`PromptService`는 원래 질문을 `{{question}}`에, FAQ의 `faqId`, `question`, `answer`를
`{{faqs}}`에 넣습니다. 검색 점수와 임베딩 벡터는 모델에 전달하지 않습니다.
시스템 지침은 SYSTEM 메시지로, 질문과 FAQ를 넣은 템플릿은 USER 메시지로 전달합니다.
입력 토큰 길이 관리와 답변 내용 검사는 별도 평가 및 구현이 필요합니다.

현재 채팅 연결은 요청마다 시스템 지침과 이번 질문·FAQ만 전달합니다.
대화 이력은 누적하거나 전달하지 않습니다.

호출 예시 (`messages`는 프롬프트 담당이 완성한 입력):

```java
LlmResponseDto response = llmService.generateAnswer(new LlmRequestDto(messages));
String answer = response.answer();
```

`LlmClient`는 통신 교체를 위한 인터페이스이며, 호출 담당자는 `LlmService`를 사용합니다.
현재 구현체는 Ollama 한 가지입니다. vLLM, 스트리밍/SSE, 관리자 설정 DB,
도구 실행과 모델 라우팅은 이 모듈에 구현하지 않았습니다.

## 설정

프로젝트 `.env` 또는 실행 환경에 설치된 모델의 정확한 태그를 지정합니다.
채팅 모델은 `ollama-init`이 받지 않으므로 `docker compose exec ollama ollama pull <모델>`로 직접 받아야 합니다.

```properties
OLLAMA_CHAT_MODEL=사용할_모델_태그
LLM_CONNECT_TIMEOUT=3s
LLM_READ_TIMEOUT=120s
```

서버 주소는 기존 `spring.ai.ollama.base-url`을 사용합니다. `local` 프로필에서는
`OLLAMA_BASE_URL`이 이 값에 연결되어 있습니다. 제한 시간은 양수여야 합니다.
기존 `OLLAMA_CONNECT_TIMEOUT` / `OLLAMA_READ_TIMEOUT`은 임베딩용이며 서로 영향을 주지 않습니다.

Spring AI 자동 구성의 공용 모델 빈 대신, LLM 전용 HTTP 제한 시간을 가진 모델 인스턴스를
`LlmClient` 내부에서 사용합니다. 새 `ChatModel` 빈을 등록하지 않아 기존 빈 선택을 바꾸지 않습니다.
모델 다운로드와 자동 재시도는 비활성화했습니다. 생성 옵션(temperature, seed, thinking 등)을
이번 모듈에서 임의로 덮어쓰지 않으며, 지정하지 않은 옵션은 Ollama/모델 기본 설정을 따릅니다.

## 오류 연결

모듈 실패는 `GlobalException`을 상속한 `LlmException`으로 전달하며 `getErrorCode()`로 구분합니다.
`llm/exception/LlmErrorCode`(`LLM-001`~`LLM-005`)는 공통 `ErrorCode`를 구현합니다.
코드별 HTTP 상태와 발생 조건은 [오류 코드 Reference](../reference/error-codes.md#llm)에 있습니다.

`ChatAnswerExecutor`는 LLM 호출 전후의 모든 실패(임베딩 `EM-*`, 벡터 검색, 유사도 미달, 프롬프트, LLM)를
시도 기록에 `FAIL`과 오류 코드로 저장한 뒤, 그 코드와 재시도 정보(`ChatResponseDto`)를 담은 `ChatException`을 전달합니다.
모델 오류의 내부 원인이나 원문은 반환하지 않으며, FAQ 원문을 성공 답변으로 대신 반환하지 않습니다.
재시도 조건은 [architecture.md](../architecture.md#재시도)를 참고하세요.

`answer_attempts_history.error_code` 컨버터는 `CHAT-*`, `EM-*`, `LLM-*`로 정의된 코드 문자열만 변환하며,
`LLM_TIMEOUT` 같은 enum 이름은 허용하지 않습니다.

이 모듈의 응답 검사는 전송·형식 검증입니다. 답변의 사실 정확성이나 FAQ 근거 충실도를
보증하지 않으며, 내용 검증은 프롬프트/출력 검사 및 별도 평가가 필요합니다.

## 로컬 테스트

```powershell
.\gradlew.bat test --tests 'com.ubot.llm.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.ai.*' --tests 'com.ubot.prompt.*'
```

테스트용 로컬 HTTP 서버와 모의 객체로 입력 검증, 모델·역할·메시지 전달,
요청 간 이력 분리, 빈 답변, 서버 오류, 파싱 실패, 제한 시간 및 재시도 횟수를 확인합니다.
채팅 연결 테스트는 테스트 전용 프롬프트(`src/test/resources/prompts/test-faq-*.txt`)와 모의 모델을 사용해 원래 질문과 여러 FAQ의 전달,
생성 답변의 HTTP 반환, 유사도 미달 시 호출 생략, 모델 오류 응답을 확인합니다.
프롬프트 누락·공백·자리표시자 누락과 입력 안의 특수문자 처리도 검사합니다.
실제 Ollama·GPU·개발 DB를 사용하지 않으며, 실제 모델의 답변 품질이나 성능을 측정하는 테스트가 아닙니다.
