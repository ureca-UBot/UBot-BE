# LLM 모듈 연결 안내

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

## 구현 범위

채팅에서 검색한 FAQ를 프롬프트에 넣어 LLM(Ollama 또는 vLLM)을 호출하고, 생성된 최종 답변을 채팅으로 반환합니다.
검색은 `ChatAnswerProcessor`가 담당하고, `AiService`가 프롬프트 구성과 LLM 호출을 연결합니다.

```text
POST /chat/questions
  → ChatService: 금지어 검사, 답변 시도 기록과 답변 작업(chat_jobs) 생성 → 202 jobId
  → Redis Streams chat:jobs → ChatJobWorker(APP_ROLE=worker): 빈 실행 슬롯에서 메시지 소비
  → ChatJobRunner: 작업 선점, 실행 마감·취소·완료 관리
  → ChatJobProcessor
  → ChatAnswerProcessor: FAQ 검색 및 각 결과의 유사도 필터링, intent별 답변 자료 수집
  → AiService.generateAnswer(AnswerMaterials)
  → PromptService.createPrompt(question, faqs, sections)
  → LlmService.generateAnswer(LlmRequestDto)
  → LlmClient: OllamaClient(기본) 또는 OpenAiCompatibleLlmClient (AI_MODE로 선택)
  → Spring AI OllamaChatModel 또는 vLLM의 OpenAI 호환 API
  → Ollama 또는 vLLM
  → LlmResponseDto(answer)
  → ChatJobCompletionService: 시도 기록·답변 로그·작업 결과를 한 트랜잭션으로 저장
  → GET /chat/jobs/{jobId}: 답변 조회
```

FAQ 질문 벡터로 후보를 검색합니다. 캐시가 승인하지 않으면 벡터 검색 순서를 유지하고, `CHAT_CONFIDENCE_THRESHOLD`로 질문 벡터 유사도를 검사합니다. 통과한 후보 중 최대 `CHAT_TOP_K`개의 답변 문장을 임베딩해 사용자 질문과 비교합니다. `FAQ_SENTENCE_SIMILARITY_THRESHOLD`를 통과한 문장만 LLM에 전달하고, 실제 통과한 FAQ를 참고 로그에 저장합니다. 두 유사도 검사 중 어느 단계에서든 통과한 FAQ가 없으면 `INSUFFICIENT_FAQ`, 검색 결과 자체가 없으면 `NO_FAQ`를 반환합니다.

프롬프트는 `src/main/resources/prompts/faq-system.txt`(시스템 지침)와
`faq-user.txt`(사용자 메시지 템플릿)로 분리되어 있고, 두 파일 모두 본문이 들어 있습니다.
파일이 없거나 비어 있거나 사용자 템플릿에 `{{question}}`과 `{{faqs}}` 중 하나라도 빠지면
LLM을 호출하지 않고 `CHAT-014`(`답변 프롬프트가 준비되지 않았습니다.`)로 실패합니다.
작성 방법은 [프롬프트 안내](../../src/main/resources/prompts/README.md)를 참고하세요.

### 출력 형식과 서버 처리

채팅 답변 지침(`faq-system-slot.txt`)은 모델에게 아래 형식의 **JSON 객체 하나**를 출력하도록 요구합니다(#277). 하위 질문마다 칸 하나이며, 표는 스키마의 키 순서(실제 출력 순서)입니다.

| 키 | 값 |
|---|---|
| `answers` | 칸 배열. 칸은 `answer`(그 하위 질문의 답, 답하지 못한 부분에 대한 말은 쓰지 않음) → `status`(`ANSWER`, `ABSTAIN`, `CONFLICT`, `OUT_OF_SCOPE`) |
| `evidence_ids` | 질문의 답이나 계산·판단의 기준을 담은 FAQ ID 문자열 배열. 그런 문서가 없으면 빈 배열 |

**출력 강제.** `PromptService.createSlotPrompt`가 칸 수만큼의 칸을 요구하는 JSON Schema(`FaqAnswerFormat.slotSchema(칸 수)`)를 `LlmRequestDto.responseSchema`에 싣고,
각 클라이언트가 이를 모델 서버에 넘깁니다. Ollama는 요청의 `format`, vLLM은 `response_format`(`json_schema`, `strict`)입니다.
칸 수는 프롬프트에 "하위 질문 N" 제목으로 나눠 넣은 경우만 하위 질문 수이고 나머지는 1입니다.
**도구 단계는 스키마 없이 진행하고, 최종 형식이 잘못됐을 때만 스키마로 1회 재호출합니다(on-broken, #287).**
최종 초안이 요청 스키마의 필수 필드·타입·status·칸 수에 맞으면 그대로 사용합니다. 코드 블록으로 감싼 정상 JSON도 허용합니다.
형식이 잘못됐으면 기존 질문·자료·도구 실행 결과를 유지하고 도구 정의 없이 다시 생성합니다. 잘못된 초안은 대화에 넣지 않습니다.
재호출 결과도 검증하며, 다시 형식이 잘못되면 `LLM-005`입니다. 서버 오류·타임아웃·길이 제한은 이 형식 복구를 유발하지 않습니다.
실제 도구를 실행하지 않았어도 도구가 제공된 요청이면 적용합니다. 칸 수는 실제 요청 스키마를 따르므로 원문을 나누지 못해 1칸으로 요청한 경우는 정상입니다.
본문의 정답 여부와 출력 가드 결과는 형식 재호출 조건이 아닙니다. 기존 출력 가드와 코드 고지는 이후에 그대로 적용합니다.
추가 설정 없이 Ollama와 OpenAI 호환 클라이언트 모두 적용하며, 로그에는 초안 검증 사유·재호출 여부·최종 검증 결과만 기록합니다.

**생성 옵션.** temperature는 `LlmClient.TEMPERATURE`(0)로 고정해 두 엔진 모두 보냅니다. 지정하지 않으면 모델 기본값
(`qwen3:4b-instruct-2507-q4_K_M`은 0.7)이 쓰여 같은 질문에도 답이 달라집니다. LLM_Test 평가도 temperature 0입니다.

**서버 처리.** `AiService`가 모델 출력을 `FaqAnswerFormat.parse()`로 읽은 뒤 칸마다 처리합니다(자세한 규칙은 [프롬프트 안내](../../src/main/resources/prompts/README.md)의 "칸 형식 지침").
- ANSWER 칸의 `answer`만 칸마다 출력 가드로 검사해 사용자 본문으로 잇습니다. 가드가 문장을 지운 칸은 ABSTAIN으로 바꿉니다.
- 답하지 못한 칸의 고지(가입 정보·매장 조회 실패는 사유별 고지)는 코드가 붙입니다. 모델이 쓴 고지는 버립니다.
- 전체 status(`AiAnswer.status`)는 하위 status를 조합한 값입니다(`ANSWER`·`PARTIAL`·`ABSTAIN`·`CONFLICT`·`OUT_OF_SCOPE`). 저장하지 않고, 가입 정보 카드를 붙일지와 미응답 수집에 씁니다.
- `question_log.llm_question`과 채팅 응답의 `answer`에는 사용자 본문(답한 칸 + 코드 고지)만 들어갑니다. `faq_log`에는 `evidence_ids`와 관계없이 모델에 전달한 FAQ 전체가 기록됩니다.

| 모델 출력 | 처리 |
|---|---|
| 칸 형식 JSON (코드 블록으로 감싼 것 포함) | 위 서버 처리. 도구 경로의 칸 수 오류는 먼저 on-broken으로 복구하며, 최종 형식 오류는 `LLM-005`. 기존 칸 합치기는 레거시 대체 처리로 유지 |
| 칸의 status가 목록에 없는 값 | `LLM-005`(`LLM_RESPONSE_INVALID`) |
| 단일 형식 JSON(`answer`·`status`) | `answer` 값을 답변으로 사용하고 본문 전체를 가드로 검사(칸 처리 없음) |
| `{`로 시작하지 않는 일반 문장 | 스키마가 있는 도구 경로는 on-broken 복구 대상. 스키마 없는 요청의 기존 일반 문장 파싱은 유지 |
| JSON처럼 보이지만 읽을 수 없음, 단일 형식에서 `answer`가 없거나 빈 문자열 | `LLM-005`로 실패. 원문 JSON을 사용자에게 보여 주지 않음 |

2026-10-12 이전에 저장된 답변은 JSON 문자열 그대로입니다. UBot-FE의 `getAnswerText`는 JSON이면 안쪽 `answer`를,
일반 문장이면 그대로 표시하므로 이전 기록과 새 기록 모두 화면에 정상으로 보입니다.

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
구현체는 `OllamaClient`와 `OpenAiCompatibleLlmClient` 두 가지이고, 답변 생성 엔진에 따라 하나만 빈으로 등록됩니다.
스트리밍/SSE, 관리자 설정 DB, 요청별 모델 라우팅은 이 모듈에 구현하지 않았습니다.

## 설정

프로젝트 `.env` 또는 실행 환경에 설치된 모델의 정확한 태그를 지정합니다.
로컬 기본값(`.env.example`)은 LLM_Test model_test_v5로 프롬프트와 함께 검증한 `qwen3:4b-instruct-2507-q4_K_M`
(`ollama list`의 ID `0edcdef34593`)입니다. 다른 모델로 바꾸면 프롬프트 검증 결과가 그대로 적용되지 않습니다.
`OLLAMA_CHAT_MODEL`에 값이 있으면 Ollama를 띄울 때(`.\tools\ubot.ps1 up`) `ollama-init`이 임베딩 모델과 함께 채팅 모델도 받습니다.
값을 나중에 채웠다면 `ollama-init`을 다시 실행하거나 직접 받습니다([명령](../quickstart.md#4-embedding-모델-준비-확인)).

```properties
OLLAMA_CHAT_MODEL=qwen3:4b-instruct-2507-q4_K_M
LLM_CONNECT_TIMEOUT=3s
LLM_READ_TIMEOUT=120s
```

서버 주소는 임베딩과 같은 `ollama.base-url`을 사용합니다. `local`과 `prod` 프로필 모두
`OLLAMA_BASE_URL`이 이 값에 연결되어 있습니다. 제한 시간은 양수여야 합니다.
기존 `OLLAMA_CONNECT_TIMEOUT` / `OLLAMA_READ_TIMEOUT`은 임베딩용이며 서로 영향을 주지 않습니다.

Spring AI 자동 구성의 공용 모델 빈 대신, LLM 전용 HTTP 제한 시간을 가진 모델 인스턴스를
`LlmClient` 내부에서 사용합니다. 새 `ChatModel` 빈을 등록하지 않아 기존 빈 선택을 바꾸지 않습니다.
모델 다운로드와 자동 재시도는 비활성화했습니다. 생성 옵션(temperature, seed, thinking 등)을
이번 모듈에서 임의로 덮어쓰지 않으며, 지정하지 않은 옵션은 Ollama/모델 기본 설정을 따릅니다.

### AI 실행 엔진 선택

`.env`의 `AI_MODE`가 답변 생성과 임베딩에 쓸 엔진을 정합니다(`AiRuntimeProperties`). 값이 없으면 `ollama`입니다.

| `AI_MODE` | 답변 생성 엔진 | 임베딩 엔진 |
|---|---|---|
| `ollama` (기본) | Ollama | Ollama |
| `vllm` | vLLM | vLLM |
| `custom` | `AI_CHAT_ENGINE`의 값 | `AI_EMBEDDING_ENGINE`의 값 |

```properties
# 답변은 vLLM으로 만들고 임베딩은 Ollama로 하는 경우
AI_MODE=custom
AI_CHAT_ENGINE=vllm
AI_EMBEDDING_ENGINE=ollama
```

- 엔진 값은 `ollama` 또는 `vllm`입니다.
- 아래 경우에는 애플리케이션이 뜨지 않습니다.
    - `AI_MODE`가 세 값 중 하나가 아니거나 비어 있음
    - `custom`인데 `AI_CHAT_ENGINE`이나 `AI_EMBEDDING_ENGINE`이 비어 있거나 허용되지 않는 값임
    - `custom`이 아닌데 `AI_CHAT_ENGINE`이나 `AI_EMBEDDING_ENGINE`에 값이 있음
- 구현체는 빈 구성 단계에서 정해지므로(`LlmConfig`, `EmbeddingConfig`) `ChatService`, `AiService`, `LlmService`, `EmbeddingService`에는 엔진별 분기가 없습니다. 값을 바꾸면 백엔드를 다시 시작해야 합니다.
- 로컬에서 모델 서버를 띄우는 `tools/ubot.ps1`도 같은 `AI_MODE`를 읽습니다. `custom`은 스크립트가 지원하지 않습니다([실행 환경](../reference/configuration.md#실행-환경-docker-compose)).
- 임베딩 엔진이 바뀌면 [Embedding Profile](#embedding-profile)이 달라지므로 [백필](#백필)이 필요합니다. 답변 생성 엔진만 바꿀 때는 필요 없습니다.

### 답변 생성 엔진

| 엔진 | 구현체 | 호출 대상 |
|---|---|---|
| `ollama` | `OllamaClient` | Ollama `/api/chat` |
| `vllm` | `OpenAiCompatibleLlmClient` | OpenAI 호환 `POST {LLM_BASE_URL}/chat/completions` |

```properties
AI_MODE=vllm
LLM_BASE_URL=http://<GPU_HOST>:8000/v1
LLM_MODEL=ubot-chat
```

- `LLM_BASE_URL`은 `/v1`까지 적고, `LLM_MODEL`은 서버의 served model name(`LLM_SERVED_MODEL_NAME`)과 같아야 합니다. 서버 실행 방법은 [LLM Serving Runtime 안내](../../infra/llm/README.md)에 있습니다.
- 제한 시간은 두 구현체 모두 `LLM_CONNECT_TIMEOUT`, `LLM_READ_TIMEOUT`을 씁니다. 자동 재시도는 하지 않습니다.
- 도구가 붙은 요청은 도구 정의를 `tools`로 보내고, LLM이 요청한 도구를 실행한 뒤 그 결과를 대화에 붙여 다시 호출합니다. 도구 실행기와 호출 한도(도구당 3회, 도구 실행 라운드 최대 8회)는 `OllamaClient`와 같습니다. 최종 형식 오류에 한해 도구 없이 스키마 재호출 1회를 적용합니다. vLLM은 `--enable-auto-tool-choice`와 `--tool-call-parser`로 실행되어 있어야 하며(`infra/llm`에 반영), 그렇지 않으면 도구가 붙은 요청을 거절합니다.
- 생성 옵션(temperature, Thinking Mode 등)은 요청에 넣지 않고 서버 기본 설정을 따릅니다. Qwen3의 Thinking Mode는 모델 서버 설정 `VLLM_ENABLE_THINKING`(기본 `false`)으로 정합니다. Thinking을 켠 서버에서 답변 앞에 `<think>…</think>`가 붙어 오면 떼어 내고 최종 답변만 반환합니다.
- 실패는 Ollama와 같은 `LlmErrorCode`로 바꿉니다. 서버가 4xx·5xx로 응답하면 `LLM-003`으로 처리하고, 상태 코드와 응답 본문 앞부분을 경고 로그에 남깁니다.
- 답변 시도 기록(`answer_attempts_history.llm_model`)에는 선택된 구현체가 요청에 넣는 모델 이름이 남습니다. `ChatAttemptsService`가 설정을 직접 읽지 않고 `LlmService.getModelName()`으로 받습니다. `ollama`면 `OLLAMA_CHAT_MODEL`, `vllm`이면 `LLM_MODEL` 값입니다.
- `LLM_MODEL`은 서버가 내보이는 이름(served model name)입니다. vLLM 서버에서 실제 모델을 바꿔도 이 이름이 같으면 시도 기록에는 같은 값이 남습니다.
- 스트리밍(SSE), 엔진별 생성 옵션 추상화, SGLang 연동은 구현하지 않았습니다.

두 구현체가 같은 동작을 하는지는 공통 계약 테스트(`LlmClientContractTest`)로 확인합니다.
`OllamaClientContractTest`와 `OpenAiCompatibleLlmClientContractTest`가 같은 테스트를 각자의 응답 형식으로 실행합니다.
실제 vLLM 서버로 확인하려면 서버를 띄운 뒤 아래처럼 실행합니다. 이 테스트는 `LLM_LIVE_BASE_URL`이 있을 때만 실행됩니다.

```powershell
$env:LLM_LIVE_BASE_URL = "http://localhost:8000/v1"
.\gradlew.bat test --tests 'com.ubot.llm.client.OpenAiCompatibleLlmClientLiveTest'
```

### 임베딩 엔진

`FaqVectorService`, `UnansweredQuestionService` 같은 호출자는 `EmbeddingService`만 사용합니다.

| 엔진 | 구현체 | 호출 대상 |
|---|---|---|
| `ollama` | `OllamaEmbeddingClient` | Ollama `/api/embed` |
| `vllm` | `OpenAiCompatibleEmbeddingClient` | OpenAI 호환 `POST {EMBEDDING_BASE_URL}/embeddings` |

```properties
AI_MODE=vllm
EMBEDDING_BASE_URL=http://<GPU_HOST>:8001/v1
EMBEDDING_MODEL=ubot-embedding
EMBEDDING_CONNECT_TIMEOUT=3s
EMBEDDING_READ_TIMEOUT=30s
```

- Ollama는 `OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_MODEL`, `OLLAMA_CONNECT_TIMEOUT`, `OLLAMA_READ_TIMEOUT`과 요청 옵션(`num_ctx=4096`, `keep_alive=30m`)을 씁니다. `EMBEDDING_BASE_URL`, `EMBEDDING_MODEL`, `EMBEDDING_*_TIMEOUT`은 vLLM 전용입니다.
- `EMBEDDING_BASE_URL`은 `/v1`까지 적고, `EMBEDDING_MODEL`은 서버의 served model name(`EMBEDDING_SERVED_MODEL_NAME`)과 같아야 합니다. 서버 실행 방법은 [LLM Serving Runtime 안내](../../infra/llm/README.md#embedding-서버)에 있습니다.
- 차원은 설정이 아니라 1024로 고정입니다(DB 컬럼 `vector(1024)`). 응답이 1024차원이 아니거나, 입력과 개수가 다르거나, 유한하지 않은 값이 있으면 `EM-002`로 거절합니다.
- 실패는 두 구현체가 같은 `EmbeddingErrorCode`로 바꿉니다. 연결 실패와 응답 지연은 `EM-003`, 서버의 4xx·5xx 응답은 `EM-001`입니다. OpenAI 호환 구현체는 4xx·5xx일 때 상태 코드와 응답 본문 앞부분을 경고 로그에 남깁니다.
- vLLM은 8192 토큰을 넘는 입력을 잘라서 처리하지 않고 400으로 거절하며, 이때는 `EM-001`이 됩니다. 채팅 질문 최대 길이인 4,000자 한국어 문장은 처리되는 것을 확인했습니다.
- 답변 시도 기록(`answer_attempts_history.embedding_model`)에는 선택된 구현체가 요청에 넣는 모델 이름이 남습니다. `ollama`면 `OLLAMA_EMBEDDING_MODEL`, `vllm`이면 `EMBEDDING_MODEL` 값입니다.

두 구현체가 같은 동작을 하는지는 공통 계약 테스트(`EmbeddingClientContractTest`)로 확인합니다.
실제 임베딩 서버로 확인하려면 서버를 띄운 뒤 아래처럼 실행합니다. 이 테스트는 `EMBEDDING_LIVE_BASE_URL`이 있을 때만 실행됩니다.

```powershell
$env:EMBEDDING_LIVE_BASE_URL = "http://localhost:8001/v1"
.\gradlew.bat test --tests 'com.ubot.embedding.client.OpenAiCompatibleEmbeddingClientLiveTest'
```

답변 생성과 임베딩의 실서버 확인 테스트를 한 번에 실행하려면 두 서버를 띄운 뒤 아래 스크립트를 씁니다.
두 서버가 준비될 때까지 기다렸다가 테스트를 실행하고, 이전 결과를 재사용하지 않도록 `cleanTest`를 함께 실행합니다.

```powershell
.\infra\llm\run-live-tests.ps1
```

| 포트 | served model name | 역할 |
|---|---|---|
| `8000` | `ubot-chat` | 답변 생성 (연결됨) |
| `8001` | `ubot-embedding` | 임베딩 (연결됨) |

### Embedding Profile

임베딩 모델은 `bge-m3`(1024차원)를 그대로 쓰고, 서버만 Ollama와 vLLM 두 가지를 씁니다. 같은 모델이어도 서버가 다르면 벡터가 호환된다고 가정하지 않으므로, 벡터를 만든 설정을 Embedding Profile로 두고 벡터를 Profile별로 따로 저장합니다.

| Profile을 정하는 값 | 출처 |
|---|---|
| `provider` | 임베딩 엔진이 `ollama`면 `ollama`, `vllm`이면 `openai-compatible` |
| `model_name` | `ollama`면 `OLLAMA_EMBEDDING_MODEL`, `vllm`이면 `EMBEDDING_MODEL` |
| `dimensions` | `1024` (고정) |
| `profile_version` | `EMBEDDING_PROFILE_VERSION` (기본 `1`, 1 이상) |

- 어느 Profile을 쓸지는 DB에 저장하지 않고 설정이 정합니다. `EmbeddingProfileService`가 벡터를 처음 저장하거나 검색할 때 위 네 값으로 `embedding_profiles`에서 Profile을 찾고, 없으면 만듭니다. 그 뒤로는 실행 중에 기억해 둔 값을 씁니다.
- 질문 임베딩과 벡터 검색에 항상 같은 Profile을 씁니다. 다른 Profile의 벡터는 검색에 쓰이지 않고 지워지지도 않습니다. 그래서 엔진을 되돌리면 전에 만든 벡터를 그대로 다시 씁니다.
- vLLM의 `model_name`은 served model name(`ubot-embedding`)입니다. 서버가 불러오는 실제 모델(`EMBEDDING_HF_MODEL`)을 바꿔도 Profile은 그대로이므로, 그때는 `EMBEDDING_PROFILE_VERSION`을 올려 새 Profile을 만들고 백필합니다. Ollama는 모델 태그가 곧 `model_name`이라 태그를 바꾸면 Profile도 바뀝니다.
- `profile_id`는 연속된 번호가 아닐 수 있습니다. 백엔드가 시작한 뒤 Profile을 처음 찾을 때 `INSERT ... ON CONFLICT DO NOTHING`을 실행하는데, 이미 있는 Profile이어도 번호를 하나 씁니다.

| 대상 | Profile별 테이블 |
|---|---|
| FAQ 질문 표현 벡터 | `faq_embeddings` (`faq_question_variants`의 활성 표현마다 현재 Profile에 `QUESTION` 유형을 저장) |
| 미응답 질문 벡터 | `unanswered_question_embeddings` |
| 미응답 묶음 중심 벡터 | `unanswered_group_embeddings` |

- FAQ 벡터에는 만들 때의 FAQ 버전(`faq_version`)이 함께 저장되고, FAQ의 현재 `version`과 같은 벡터만 검색에 쓰입니다.
- FAQ를 만들면 원본 질문과 유사 질문을 각각 variant로 저장하고, 현재 Profile에 각 variant의 질문 벡터를 만듭니다. 수정 시 유지되는 variant는 질문 벡터의 `faq_version`을 갱신합니다. 원본 질문이 바뀌면 기존 variant를 모두 종료하고 새로 만듭니다. 다른 Profile의 벡터는 옛 버전으로 남아 검색에서 빠지고, 그 Profile로 돌아갔을 때 백필이 다시 만듭니다.
- `faq_embeddings.vector_type`은 과거 유형도 허용하지만, 애플리케이션은 `QUESTION`만 새로 만듭니다.
- 벡터는 위 테이블에만 있습니다. 예전 벡터 컬럼(`faq.vector`, `old_faq.vector`, `unanswered_questions.question_vector`, `unanswered_question_groups.centroid`)은 `V25`에서 지웠고, FAQ 수정 이력에는 벡터를 남기지 않습니다.

### FAQ 질문 벡터와 답변 문장 비교

FAQ의 활성 질문 표현(variant)마다 현재 Profile에 질문 벡터를 저장합니다. 사용자 질문도 임베딩해 `QUESTION` 벡터와 비교하고, FAQ마다 점수가 가장 높은 표현 하나를 고릅니다. 캐시가 승인하지 않은 경우 질문 벡터 유사도 기준을 통과한 후보 FAQ의 답변을 임베딩해 사용자 질문과 비교합니다. 일반 문장은 문장별로, 안내 문장과 이어지는 번호·글머리 목록은 목록 전체를 하나의 단위로 비교합니다. 목록 뒤에 들여 쓰지 않고 시작하는 일반 문장은 별도로 비교합니다. `FAQ_SENTENCE_SIMILARITY_THRESHOLD`를 통과한 단위만 LLM에 전달합니다.

FAQ 생성 시 원본과 유사 질문 각각의 질문 벡터를 만듭니다. 원본 질문이나 유사 질문이 바뀌면 새 variant의 질문을 임베딩하고 제거된 variant의 벡터를 삭제합니다. 답변·카테고리·intent·cacheable만 바뀐 경우 유지되는 질문 벡터의 `faq_version`만 갱신합니다.

아래 SQL에서 활성 variant 수와 질문 벡터 준비 수를 비교할 수 있습니다. `<profile_id>`는 지금 Profile의 ID입니다.

```sql
SELECT count(*) AS active_variant_count,
       count(q.faq_question_variant_id) AS question_ready
FROM faq_question_variants v
JOIN faq f ON f.id = v.origin_faq_id
LEFT JOIN faq_embeddings q
       ON q.faq_question_variant_id = v.id AND q.profile_id = <profile_id>
      AND q.vector_type = 'QUESTION' AND q.faq_version = f.version
WHERE f.deleted_at IS NULL;
```

- 배포 스크립트는 현재 Profile의 `QUESTION` 벡터만 확인하고 누락분을 백필합니다([배포 주의할 점](../deploy.md#주의할-점)).
- **FAQ 검색은 기존 HNSW cosine 인덱스로 후보를 찾습니다.** 현재 Profile·유형·FAQ 버전·삭제 여부·intent 조건을 후보 조회 안에 적용하고, 거리 오름차순과 LIMIT으로 검색합니다. 후보 안에서 FAQ별 최고 점수 variant를 고르고, 중복 제거 후 topK가 부족하면 후보 수를 두 배로 늘립니다.
- 검색 트랜잭션에만 `hnsw.ef_search=200`, `hnsw.iterative_scan=strict_order`를 적용합니다. 필터로 초기 후보가 제거되면 추가 탐색하지만, HNSW는 근사 검색이므로 전체 비교와 동일한 결과를 보장하지 않습니다. 탐색·메모리 상한은 pgvector 기본값이며 작은 테이블에서는 DB가 순차 검색을 선택할 수 있습니다. [FAQ HNSW 검색 테스트 보고서](../FAQ_HNSW_검색_테스트_보고서.md)를 참고하세요. 종전 exact 전환 측정은 [FAQ 검색·답변 테스트 보고서](../FAQ_검색_테스트_보고서.md)에 보존합니다.

### 백필

지금 Profile에 현재 FAQ 버전의 `QUESTION` 벡터가 없는 활성 variant만 임베딩해 채우는 관리자 API입니다. 임베딩 엔진, 모델, `EMBEDDING_PROFILE_VERSION` 중 하나를 바꾼 뒤에 실행합니다. 로컬에서는 백엔드가 시작할 때 자동으로 실행되지 않으므로 직접 실행합니다.

배포에서는 `tools/deploy/deploy.sh`가 대신 실행합니다. 벡터가 모자라면 백엔드를 교체하기 전에 새 이미지를 `--app.embedding-backfill.run=true` 인자로 띄우는데, 이렇게 뜬 백엔드는 아래 두 백필을 차례로 실행하고 종료합니다. 종료 코드는 `0`(완료), `3`(적용되지 않은 마이그레이션이 있어 건너뜀), 그 밖(실패)입니다. 이 실행에서는 DB 마이그레이션을 적용하지 않습니다([배포 단계](../deploy.md#워크플로우-단계)).

현재 시드 스크립트(`tools/faq-db/scripts/reset-local-db.ps1`, `tools/faq-db/scripts/reset-faq-data.ps1`)는 FAQ와 질문 표현만 적재합니다. 시드 적재 뒤에는 백필로 검색에 필요한 `QUESTION` 벡터를 채워야 합니다([기본 데이터로 한 번에 채우기](local-data.md#기본-데이터로-한-번에-채우기)).

| API (JWT + `ADMIN`) | 채우는 것 | 응답의 `data` |
|---|---|---|
| `POST /admin/faqs/embeddings/backfill` | 활성 FAQ의 활성 variant 중 지금 Profile에 현재 FAQ 버전의 `QUESTION` 벡터가 없는 것 | 새로 만든 벡터 수 (variant 하나에 최대 1개) |
| `POST /admin/unanswered-groups/embeddings/backfill` | 지금 Profile에 벡터가 없는 미응답 질문. 끝에 묶음별 중심 벡터를 지금 Profile의 질문 벡터 평균으로 다시 계산 | 새로 임베딩한 질문 수 |

전환 순서는 다음과 같습니다.

1. `.env`에서 `AI_MODE`(또는 모델 이름, `EMBEDDING_PROFILE_VERSION`)를 바꿉니다.
2. 모델 서버를 띄웁니다(`.\tools\ubot.ps1 up`).
3. 백엔드를 다시 시작합니다.
4. 백필 스크립트를 실행합니다. `ADMIN` 계정의 비밀번호를 물어본 뒤 두 API를 차례로 호출합니다.

```powershell
.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>
```

- `newly embedded =` 뒤의 숫자가 새로 채운 건수입니다. `0`이면 채울 것이 없다는 뜻이고, 여러 번 실행해도 됩니다.
- 스크립트는 API를 호출할 때마다 다시 로그인합니다. 백필이 access token 유효 시간보다 길어질 수 있기 때문입니다. 임베딩 서버 오류(`EM-*`)로 멈추면 최대 3번까지 이어서 시도합니다.
- 다른 주소의 백엔드에는 `-BaseUrl http://<주소>`를 붙입니다. 요청 하나를 기다리는 시간은 `-TimeoutSec`(기본 3600초)로 바꿉니다.
- 실행 정책 때문에 막히면 `powershell -ExecutionPolicy Bypass -File .\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>`로 실행합니다.
- 스크립트 없이 하려면 Swagger UI(`/swagger-ui.html`)에서 `POST /auth/login`으로 받은 access token을 `Authorize`에 넣고 위 두 API를 차례로 실행합니다. macOS/Linux에서도 이 방법을 씁니다.
- 요청은 백필이 끝난 뒤에 응답합니다. 누락된 활성 variant마다 임베딩하므로 호출하는 쪽의 제한 시간을 넉넉히 잡으세요. 배포 서버의 Nginx는 백필 경로에만 응답 제한 시간 3600초를 적용합니다.
- 한 건씩 바로 저장하므로, 도중에 임베딩 서버 오류로 멈추거나 연결이 끊겨도 그때까지 저장한 벡터는 남습니다. 다시 실행하면 나머지를 채웁니다.

백필하기 전에는 지금 Profile에 벡터가 없어서 아래처럼 동작합니다. 사용자 요청을 받기 전에 백필을 끝내세요.

| 기능 | 백필 전 동작 |
|---|---|
| 채팅의 FAQ 검색 | 검색 결과가 없어 `CHAT-012`로 끝남 |
| FAQ 수정 | 새 variant의 질문 벡터는 만들어짐. 유지 variant의 `QUESTION` 벡터가 없으면 버전 갱신만으로는 채워지지 않음 |
| FAQ 생성 | 정상. 지금 Profile에 모든 새 variant의 질문 벡터가 만들어짐 |
| 미응답 질문 묶기 | 지금 Profile의 중심 벡터가 없어 기존 묶음을 찾지 못하고 새 묶음을 만듦 |

Profile별로 벡터가 얼마나 채워졌는지는 DB에서 확인합니다([DB 접속](db-access.md)).

```sql
SELECT p.profile_id, p.provider, p.model_name, p.profile_version,
       count(fe.faq_question_variant_id) FILTER (WHERE fe.vector_type = 'QUESTION') AS question_vectors,
       count(fe.faq_question_variant_id) FILTER (WHERE fe.vector_type = 'QUESTION_ANSWER') AS question_answer_vectors
FROM embedding_profiles p
LEFT JOIN faq_embeddings fe
       ON fe.profile_id = p.profile_id
GROUP BY p.profile_id
ORDER BY p.profile_id;
```

## 오류 연결

모듈 실패는 `GlobalException`을 상속한 `LlmException`으로 전달하며 `getErrorCode()`로 구분합니다.
`llm/exception/LlmErrorCode`(`LLM-001`~`LLM-005`)는 공통 `ErrorCode`를 구현합니다.
코드별 HTTP 상태와 발생 조건은 [오류 코드 Reference](../reference/error-codes.md#llm)에 있습니다.

`ChatJobProcessor`는 LLM 호출 전후의 모든 실패(임베딩 `EM-*`, 벡터 검색, 유사도 미달, 프롬프트, LLM)를 오류 코드로 바꾸고,
Worker가 그 코드를 시도 기록의 `FAIL`과 작업 결과에 저장합니다. 클라이언트는 상태 조회의 `errorCode`와 `retryable`로 받습니다.
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
