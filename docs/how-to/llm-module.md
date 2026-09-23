# LLM 모듈 연결 안내

## 구현 범위

채팅에서 검색한 FAQ를 프롬프트에 넣어 Ollama를 호출하고, 생성된 최종 답변을 채팅으로 반환합니다.
검색은 기존 `ChatService`가 담당하고, `AiService`가 프롬프트 구성과 LLM 호출을 연결합니다.

```text
POST /chat/questions
  → ChatService: FAQ 검색 및 기존 유사도 판정
  → AiService.generateAnswer(question, results)
  → PromptService.createPrompt(question, results)
  → LlmService.generateAnswer(LlmRequestDto)
  → LlmClient / OllamaClient
  → Spring AI OllamaChatModel
  → Ollama
  → LlmResponseDto(answer)
  → ChatResponseDto: 채팅 응답
```

기존 TOP-K 3개 검색과 가장 높은 점수의 0.75 기준은 유지합니다. 기준을 통과하면
원래 질문과 검색 결과 전체를 전달하며, FAQ를 다시 조회하지 않습니다.
모든 검색 결과가 각각 0.75 이상이어야 하는 구조는 아닙니다.

승지님이 작성할 최종 프롬프트는 `src/main/resources/prompts/faq-system.txt`,
`faq-user.txt`로 분리했고, 현재 두 파일의 본문은 의도적으로 비워 두었습니다.
시스템 지침과 사용자 템플릿이 준비되기 전에는 LLM을 호출하지 않고
`답변 프롬프트가 아직 준비되지 않았습니다.`라는 실패 응답을 반환합니다.
사용자 템플릿에는 `{{question}}`과 `{{faqs}}`가 모두 필요합니다.
작성 방법은 [프롬프트 안내](../../src/main/resources/prompts/README.md)를 참고하세요.

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
실제 `.env` 작성과 모델 설치·다운로드는 이 작업에서 수행하지 않았습니다.

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

모듈 실패는 `LlmException`으로 전달하며 `getErrorCode()`로 구분합니다.

| 코드 | 의미 |
|---|---|
| `LLM_REQUEST_INVALID` | 입력 메시지 누락·공백 또는 마지막 역할 오류 |
| `LLM_MODEL_NOT_CONFIGURED` | 모델명 미설정 |
| `LLM_SERVICE_UNAVAILABLE` | 연결 거부 또는 서버 오류 등 |
| `LLM_TIMEOUT` | HTTP 연결·응답 제한 시간 초과 |
| `LLM_RESPONSE_INVALID` | 응답 파싱 실패, 최종 답변 누락, 길이 제한 종료, 지원하지 않는 도구 호출 |

`ChatService`는 `PromptException`과 `LlmException`을 기존 `ChatResponseDto`의 실패 응답으로
변환합니다. 프롬프트 미준비, 모델 미설정, 시간 초과 등의 경우 `data.success`가 `false`이며
`data.answer`에는 안내 문구가 들어갑니다. 모델 오류의 내부 원인이나 원문은 반환하지 않습니다.
실패 시 기존 FAQ 원문을 성공 답변으로 대신 반환하지 않습니다.

기존 컨트롤러 계약에 따라 이러한 응답의 HTTP 상태는 200이고, 바깥쪽 `ApiResponse.success`는
`true`입니다. 답변 생성 성공 여부는 `data.success`로 확인합니다.
공통 `ErrorCode`와 `GlobalExceptionHandler`는 수정하지 않았습니다.

이 모듈의 응답 검사는 전송·형식 검증입니다. 답변의 사실 정확성이나 FAQ 근거 충실도를
보증하지 않으며, 내용 검증은 프롬프트/출력 검사 및 별도 평가가 필요합니다.

## 로컬 테스트

```powershell
.\gradlew.bat test --tests 'com.ubot.llm.*' --tests 'com.ubot.chat.*' --tests 'com.ubot.ai.*' --tests 'com.ubot.prompt.*'
```

테스트용 로컬 HTTP 서버와 모의 객체로 입력 검증, 모델·역할·메시지 전달,
요청 간 이력 분리, 빈 답변, 서버 오류, 파싱 실패, 제한 시간 및 재시도 횟수를 확인합니다.
채팅 연결 테스트는 테스트 전용 프롬프트와 모의 모델을 사용해 원래 질문과 여러 FAQ의 전달,
생성 답변의 HTTP 반환, 유사도 미달 시 호출 생략, 모델 오류 응답을 확인합니다.
프롬프트 누락·공백·자리표시자 누락과 입력 안의 특수문자 처리도 검사합니다.
실제 Ollama·GPU·개발 DB를 사용하지 않으며, 실제 모델의 답변 품질이나 성능을 측정하는 테스트가 아닙니다.
