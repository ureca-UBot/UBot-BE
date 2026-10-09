# 설정 Reference

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

## 설정 파일

```text
src/main/resources/
├── application.yml         # 모든 환경 공통 (Flyway, JWT, actuator, AI 실행 엔진)
├── application-local.yml   # 로컬 개발 (기본 프로필)
├── application-prod.yml    # 운영
└── application-test.yml    # 로컬·CI의 자동 테스트
```

| 프로필 | 사용 환경 | 활성화 방법 | 접속 정보 공급원 |
|---|---|---|---|
| `local` | 개발자 PC (`bootRun`, Eclipse) | 기본값 (`spring.profiles.default: local`) | 프로젝트 루트의 `.env` 또는 OS 환경변수 |
| `prod` | 운영 서버 | OS 환경변수 `SPRING_PROFILES_ACTIVE=prod` | 컨테이너에 주입된 환경변수. `.env` 파일을 직접 읽지 않음 |
| `test` | 로컬·GitHub Actions의 자동 테스트 | `gradlew test`는 `build.gradle`에서 `spring.profiles.active=test`를 강제. IDE에서 JUnit을 직접 실행하면 테스트 클래스의 `@ActiveProfiles("test")`가 적용 | Testcontainers + `@ServiceConnection` |

자동 테스트는 별도 DB 컨테이너의 임의 호스트 포트와 접속 정보를 사용합니다. 개발 `.env`, 고정 포트, 개발 DB·볼륨을 공유하지 않습니다. `test` 프로필만 지정해 `bootRun`을 실행하는 것은 Testcontainers 기반 테스트 실행과 다릅니다.

`prod`는 `local`과 달리 접속 정보에 기본값이 없고(`POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `REDIS_HOST` 필수), health 상세를 숨기고, Swagger UI와 API 문서를 끄고, 종료할 때 처리 중인 요청을 최대 30초 기다립니다. 지금 배포 구성은 백엔드를 `local`로 띄우므로 `prod`는 아직 쓰이지 않습니다([deploy.md](../deploy.md#주의할-점)).

## 실행 환경 (Docker Compose)

개발용 컨테이너는 공통 파일에 모델 서버 파일 하나를 더해서 띄웁니다. 어느 파일을 쓸지는 `.env`의 `AI_MODE`가 정하고, `tools/ubot.ps1`이 그 값에 맞는 파일을 골라 줍니다. 실행 순서는 [quickstart](../quickstart.md#실행-환경-선택)에 있습니다.

| 파일 | 서비스 | 쓰는 경우 |
|---|---|---|
| `docker-compose.yml` | `postgres`, `redis`, `prometheus` | 항상 |
| `docker-compose.ollama.yml` | `ollama`, `ollama-init` | `AI_MODE=ollama` (기본) |
| `docker-compose.vllm.yml` | `vllm`(답변 생성), `vllm-embedding`(임베딩) | `AI_MODE=vllm`. NVIDIA GPU 필요 |

| 명령 | 하는 일 |
|---|---|
| `.\tools\ubot.ps1 up` | 다른 쪽 모델 서버 컨테이너를 내린 뒤, 공통 서비스와 `AI_MODE`에 맞는 모델 서버를 띄웁니다. `vllm`이면 두 서버의 `/v1/models`에 `LLM_MODEL`, `EMBEDDING_MODEL`이 보일 때까지 서버마다 최대 300초 기다립니다 |
| `.\tools\ubot.ps1 down` | 세 파일의 컨테이너를 모두 내립니다. 볼륨은 남깁니다 |
| `.\tools\ubot.ps1 status` | 세 파일의 컨테이너 상태를 보여 줍니다 |

- 스크립트는 `docker compose -f <공통 파일> -f <모델 서버 파일> --env-file .env ...`를 실행합니다. 직접 실행할 때도 `-f`로 두 파일을 함께 지정합니다. `-f` 없이 실행하면 `docker-compose.yml`만 읽습니다. 그래서 `up`은 공통 서비스만 띄우고, `logs ollama`나 `run ollama-init`처럼 모델 서버를 지정한 명령은 `no such service`로 실패합니다.
- 두 모드는 같은 Compose 프로젝트(폴더 이름을 따름. 보통 `ubot-be`)와 같은 PostgreSQL 볼륨을 씁니다. 한 DB에 두 서버의 벡터가 Profile별로 따로 저장됩니다([Embedding Profile](../how-to/llm-module.md#embedding-profile)).
- `.env`에 `AI_MODE`가 없으면 스크립트는 실행되지 않습니다. `AI_MODE=custom`도 지원하지 않으므로, 그때는 필요한 모델 서버를 직접 띄웁니다.
- `docker-compose.vllm.yml`은 `infra/llm/docker-compose.yml`의 서비스 정의를 `extends`로 가져옵니다. 이때 값은 `infra/llm/.env`가 아니라 루트 `.env`에서 읽습니다. 루트 `.env`에 없는 값은 `infra/llm/docker-compose.yml`의 기본값을 씁니다.
- 받아 둔 vLLM 모델은 `llm_hf-cache` 볼륨에 있습니다. `infra/llm`에서 따로 띄운 서버와 같은 볼륨이라 모델을 다시 받지 않습니다.
- 배포는 `-p ubot`과 `-f`로 프로젝트 이름과 파일을 직접 지정합니다([deploy.md](../deploy.md)).

## 개발 환경변수

`.env.example`을 복사했을 때의 값과 Spring YAML의 fallback을 구분합니다. fallback이 "없음"인 값은 `.env` 또는 OS 환경변수로 반드시 제공해야 하며, 없으면 기동 시점에 `Could not resolve placeholder` 오류가 납니다.

| 변수 | 사용처 | `.env.example` 값 | Spring fallback |
|---|---|---|---|
| `AI_MODE` | `AiRuntimeProperties`(`app.ai.mode`), `tools/ubot.ps1`, CD: 답변 생성과 임베딩에 쓸 모델 서버. `ollama`, `vllm`, `custom` | `ollama` | 키가 없으면 `ollama`. 빈 값이거나 다른 값이면 기동되지 않음 |
| `AI_CHAT_ENGINE` | `AiRuntimeProperties`: `AI_MODE=custom`일 때 답변 생성 엔진. `ollama` 또는 `vllm` | 주석 처리됨 | 빈 값. `custom`인데 비어 있거나 `custom`이 아닌데 값이 있으면 기동되지 않음 |
| `AI_EMBEDDING_ENGINE` | `AiRuntimeProperties`: `AI_MODE=custom`일 때 임베딩 엔진. `ollama` 또는 `vllm` | 주석 처리됨 | 빈 값. 조건은 `AI_CHAT_ENGINE`과 같음 |
| `EMBEDDING_PROFILE_VERSION` | `EmbeddingProfileService`: Embedding Profile의 버전. 엔진과 모델 이름이 그대로인데 벡터를 새로 만들어야 할 때 올림 | `1` | `1` |
| `AI_RUNTIME_MANAGED` | CD: 모델 서버를 배포 서버에 함께 띄울지 여부. `true` 또는 `false` ([deploy.md](../deploy.md#서버의-env)) | `true` | Spring에서 읽지 않음 |
| `POSTGRES_HOST` | Spring | `localhost` | `localhost` |
| `POSTGRES_PORT` | Docker, Spring | `15432` | `15432` |
| `POSTGRES_DB` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_USER` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_PASSWORD` | Docker, Spring | `change-me` (**변경 필수**) | 없음 |
| `OLLAMA_PORT` | Docker의 호스트 포트 | `11435` | Spring에서 읽지 않음 |
| `OLLAMA_BASE_URL` | Spring (`spring.ai.ollama`, `ollama`) | `http://localhost:11435` | `http://localhost:11435` |
| `OLLAMA_EMBEDDING_MODEL` | Spring, `ollama-init` | `bge-m3:567m` | `bge-m3:567m` |
| `OLLAMA_CHAT_MODEL` | Spring (`spring.ai.ollama.chat.model`), `LlmConfig`, `ollama-init`(값이 있으면 채팅 모델도 내려받음) | 빈 값 (사용할 모델 지정) | 빈 값. 줄이 없어도 기동되며 `LlmConfig`는 빈 값 허용 |
| `LLM_CONNECT_TIMEOUT` | LLM 전용 HTTP 연결 제한 시간 | `3s` | `LlmConfig` 기본값 `3s` |
| `LLM_READ_TIMEOUT` | LLM 전용 HTTP 응답 제한 시간 | `120s` | `LlmConfig` 기본값 `120s` |
| `LLM_BASE_URL` | `LlmConfig`: 답변 생성 엔진이 `vllm`일 때 호출할 OpenAI 호환 API 주소(`/v1`까지) | `http://localhost:8000/v1` | `http://localhost:8000/v1` |
| `LLM_MODEL` | `LlmConfig`: 답변 생성 엔진이 `vllm`일 때 요청에 넣는 모델 이름. `LLM_SERVED_MODEL_NAME`과 같아야 함. `tools/ubot.ps1`의 준비 확인에도 사용 | `ubot-chat` | `ubot-chat` |
| `LLM_PORT` | Docker Compose: vLLM 답변 생성 서버의 호스트 포트. `tools/ubot.ps1`의 준비 확인에도 사용 | `8000` | Spring에서 읽지 않음 |
| `LLM_HF_MODEL` | Docker Compose: vLLM이 Hugging Face에서 불러올 답변 생성 모델 | `Qwen/Qwen3-4B-AWQ` | Spring에서 읽지 않음 |
| `LLM_SERVED_MODEL_NAME` | Docker Compose: vLLM이 API에 내보이는 답변 생성 모델 이름 | `ubot-chat` | Spring에서 읽지 않음 |
| `LLM_MAX_MODEL_LEN` | Docker Compose: vLLM 답변 생성 서버가 받는 최대 토큰 길이(`--max-model-len`) | `4096` | Spring에서 읽지 않음 |
| `VLLM_GPU_MEMORY_UTILIZATION` | Docker Compose: vLLM 답변 생성 서버가 쓸 GPU 메모리 비율 | `0.6` | Spring에서 읽지 않음 |
| `VLLM_TOOL_CALL_PARSER` | Docker Compose: vLLM 답변 생성 서버의 tool call parser | `hermes` | Spring에서 읽지 않음 |
| `VLLM_ENABLE_THINKING` | Docker Compose: vLLM 답변 생성 서버의 thinking 모드 기본값 | `false` | Spring에서 읽지 않음 |
| `CHAT_MAX_ATTEMPTS` | `ChatAttemptsService`: 최초 요청을 포함한 최대 답변 생성 시도 횟수 | `3` | `3` |
| `CHAT_TOP_K` | `ChatAnswerProcessor`: FAQ 검색 시 요청하는 최대 결과 개수 | `3` | `3` |
| `CHAT_CONFIDENCE_THRESHOLD` | `ChatAnswerProcessor`: 검색된 **각** FAQ를 LLM에 전달할지 정하는 유사도 기준. 미만인 FAQ는 제외하고, 남은 FAQ가 없으면 LLM을 호출하지 않음 | `0.75` | `0.75` |
| `CHAT_RESPONSE_TIMEOUT_MILLIS` | `ChatController`: 채팅 응답 대기 제한 시간(밀리초) | `180000` (180초) | `180000` |
| `UNANSWERED_GROUP_THRESHOLD` | `UnansweredQuestionService`: 미응답 질문을 기존 묶음에 넣을지 정하는 묶음 중심 벡터와의 최소 코사인 유사도 | `0.6` | `0.6` |
| `OLLAMA_CONNECT_TIMEOUT` | `EmbeddingConfig`: Ollama 임베딩 연결 제한 시간 | `3s` | `3s` |
| `OLLAMA_READ_TIMEOUT` | `EmbeddingConfig`: Ollama 임베딩 응답 제한 시간 | `10s` | `10s` |
| `EMBEDDING_BASE_URL` | `EmbeddingConfig`: 임베딩 엔진이 `vllm`일 때 호출할 OpenAI 호환 API 주소(`/v1`까지) | `http://localhost:8001/v1` | `http://localhost:8001/v1` |
| `EMBEDDING_MODEL` | `EmbeddingConfig`: 임베딩 엔진이 `vllm`일 때 요청에 넣는 모델 이름. `EMBEDDING_SERVED_MODEL_NAME`과 같아야 함. Embedding Profile의 모델 이름으로도 기록됨 | `ubot-embedding` | `ubot-embedding` |
| `EMBEDDING_PORT` | Docker Compose: vLLM 임베딩 서버의 호스트 포트. `tools/ubot.ps1`의 준비 확인에도 사용 | `8001` | Spring에서 읽지 않음 |
| `EMBEDDING_HF_MODEL` | Docker Compose: vLLM이 Hugging Face에서 불러올 임베딩 모델 | `BAAI/bge-m3` | Spring에서 읽지 않음 |
| `EMBEDDING_SERVED_MODEL_NAME` | Docker Compose: vLLM이 API에 내보이는 임베딩 모델 이름 | `ubot-embedding` | Spring에서 읽지 않음 |
| `EMBEDDING_MAX_MODEL_LEN` | Docker Compose: vLLM 임베딩 서버가 받는 최대 토큰 길이 | `8192` | Spring에서 읽지 않음 |
| `EMBEDDING_DTYPE` | Docker Compose: vLLM 임베딩 모델의 dtype | `float16` | Spring에서 읽지 않음 |
| `VLLM_EMBEDDING_GPU_MEMORY_UTILIZATION` | Docker Compose: vLLM 임베딩 서버가 쓸 GPU 메모리 비율 | `0.2` | Spring에서 읽지 않음 |
| `EMBEDDING_CONNECT_TIMEOUT` | `EmbeddingConfig`: vLLM 임베딩 연결 제한 시간 | `3s` | `3s` |
| `EMBEDDING_READ_TIMEOUT` | `EmbeddingConfig`: vLLM 임베딩 응답 제한 시간 | `30s` | `30s` |
| `HF_TOKEN` | Docker Compose: vLLM이 모델을 받을 때 쓰는 Hugging Face 토큰. 공개 모델만 쓰면 비워 둠 | 빈 값 | Spring에서 읽지 않음 |
| `VLLM_IMAGE` | Docker Compose: vLLM 이미지 | `vllm/vllm-openai:v0.31.0` | Spring에서 읽지 않음 |
| `BACKEND_IMAGE` | Docker Compose(`docker-compose.deploy.yml`): 백엔드 이미지. CD는 `ubot-be:<커밋 SHA>`로 덮어씀 | `ubot-be:local` | Spring에서 읽지 않음 |
| `KAKAO_REST_API_KEY` | `KakaoLocalClient`(주소 검색), `KakaoDirectionsClient`(길찾기) | 빈 값 | 없음 |
| `JWT_SECRET` | `JwtUtil` | 안내 문구 (**변경 필수**) | 없음 |
| `JWT_ACCESS_TOKEN_EXPIRATION_MILLIS` | `JwtUtil` | `600000` (10분) | `3600000` (1시간) |
| `JWT_REFRESH_TOKEN_EXPIRATION_DAYS` | `RefreshTokenService` | `14` | `14` |
| `SPRING_PROFILES_ACTIVE` | OS 환경변수로 제공하면 프로필 선택. CD는 서버 `.env`의 이 값이 `prod`인지 검사 | `local` | `.env`에 적는 것만으로는 프로필을 바꾸지 못함 |

Compose는 PostgreSQL `127.0.0.1:15432 → 5432`, Ollama `127.0.0.1:11435 → 11434`로 노출합니다. `OLLAMA_PORT`를 바꾸면 Spring이 사용하는 `OLLAMA_BASE_URL`의 포트도 함께 바꿔야 합니다.

`AI_MODE=vllm`에서는 Ollama 대신 답변 생성 서버를 `127.0.0.1:8000`, 임베딩 서버를 `127.0.0.1:8001`로 노출합니다. `LLM_PORT`, `EMBEDDING_PORT`를 바꾸면 `LLM_BASE_URL`, `EMBEDDING_BASE_URL`의 포트도 함께 바꿔야 합니다.

`LLM_MODEL`과 `LLM_SERVED_MODEL_NAME`, `EMBEDDING_MODEL`과 `EMBEDDING_SERVED_MODEL_NAME`은 각각 같은 값이어야 합니다. 앞의 것은 백엔드가 요청에 넣는 이름이고 뒤의 것은 vLLM이 내보이는 이름입니다. 불러올 실제 모델을 바꿀 때는 이 이름을 그대로 두고 `LLM_HF_MODEL`, `EMBEDDING_HF_MODEL`만 바꿉니다. 다만 임베딩 모델을 바꾸면 이름이 같아도 벡터가 달라지므로 `EMBEDDING_PROFILE_VERSION`을 올리고 백필합니다([Embedding Profile](../how-to/llm-module.md#embedding-profile)).

`CHAT_CONFIDENCE_THRESHOLD`의 기본값 `0.75`는 threshold 보고서의 채택값 `0.73`과 다릅니다. 배경은 [architecture.md](../architecture.md#현재-상태와-남은-작업)를 참고하세요. 보고서 값을 쓰려면 `.env`에서 `CHAT_CONFIDENCE_THRESHOLD=0.73`으로 지정합니다.


`CHAT_MAX_ATTEMPTS`를 바꿔도 한도 초과 오류(`CHAT-006`)의 안내 문구는 "최대 3회"로 고정되어 있습니다.

### 환경변수 없이 코드 기본값만 있는 설정

아래 값은 YAML과 `.env.example`에 없고 코드의 기본값을 사용합니다. 바꾸려면 설정 키로 지정합니다.

| 설정 키 | 기본값 | 사용처 |
|---|---|---|
| `kakao.directions.connect-timeout` | `3s` | `KakaoDirectionsClient` |
| `kakao.directions.read-timeout` | `5s` | `KakaoDirectionsClient` |
| `prompt.faq.system-location` | `classpath:prompts/faq-system.txt` | `PromptService` |
| `prompt.faq.user-location` | `classpath:prompts/faq-user.txt` | `PromptService` |
| `spring.ai.ollama.chat.options.model` | 없음 (`OLLAMA_CHAT_MODEL`로 대체) | `LlmConfig` |

### 키가 있지만 값이 비어 있을 때

`OLLAMA_CHAT_MODEL`과 `KAKAO_REST_API_KEY`는 `.env.example`에서 빈 값입니다. 빈 값이면 기동은 되지만 해당 기능이 실패합니다(채팅 모델 미지정, 카카오 API 인증 실패).

- `KAKAO_REST_API_KEY`는 키 줄 자체를 지우면 기동이 실패합니다.
- `OLLAMA_CHAT_MODEL`은 줄이 없어도 빈 값으로 처리되어 기동됩니다. 답변 생성 엔진이 `vllm`이면 이 값을 쓰지 않으므로 비워 둬도 됩니다.
- `AI_MODE`는 줄이 없으면 `ollama`로 기동되지만, `AI_MODE=`처럼 값만 비워 두면 기동이 실패합니다.

### `JWT_SECRET`은 반드시 교체해야 합니다

`JwtUtil`은 생성 시점에 이 값을 Base64로 디코딩해 HMAC 키를 만듭니다. `.env.example`의 기본값은 안내 문구여서 디코딩에 실패하고, 애플리케이션이 기동되지 않습니다.

```text
io.jsonwebtoken.io.DecodingException: Illegal base64 character: '-'
```

Base64로 인코딩된 32바이트 이상의 값이 필요합니다. 생성 방법은 [quickstart](../quickstart.md#2-env-만들기)에 있습니다.

### 값을 읽는 순서

- `local` 프로필은 `spring.config.import: optional:file:.env[.properties]`로 `.env`를 읽습니다.
- OS 환경변수가 `.env`보다 우선합니다. 나중에 실제 환경변수로 주입하면 코드 수정 없이 그 값이 사용됩니다.
- `.env`는 **실행 위치 기준 상대경로**로 찾습니다. 프로젝트 루트가 아닌 곳에서 실행하면 읽지 못합니다.
- Docker Compose도 같은 `.env`를 읽지만 **파싱 문법이 다르므로 같은 값을 읽는다고 보장할 수 없습니다.** 아래 제약을 확인하세요.

### `.env` 파싱 제약 (아직 미해결)

Docker Compose는 dotenv 문법을, Spring은 Java properties 문법을 사용합니다.

- 예를 들어 `POSTGRES_PASSWORD='example'`이면 Compose는 인용부호를 제거하지만 Spring은 인용부호를 비밀번호에 포함합니다.
- Spring Boot 4.1.1의 properties 로딩은 인코딩 미지정 시 ISO-8859-1을 사용하므로 UTF-8로 저장한 한글 등 비ASCII 값이 달라질 수 있습니다.
- `$`, 역슬래시, 따옴표, 공백, 인라인 주석도 두 파서에서 의미가 다를 수 있습니다.

당장은 **따옴표 없이 충분히 긴 임의의 영문·숫자 값**을 쓰고 값 뒤에 주석을 붙이지 않는 방식으로 충돌을 피하세요. 임시 회피책이며 근본 해결은 아직 적용하지 않았습니다. [Docker Compose의 dotenv 구문](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#env-file-syntax)도 참고하세요.

### `SPRING_PROFILES_ACTIVE`가 `.env`에서 효과 없는 이유

`.env`는 `local` 프로필 설정 안에서 읽히므로, 읽는 시점에는 이미 프로필이 결정되어 있습니다. 프로필을 바꾸려면 OS 환경변수나 실행 인자로 지정해야 합니다.

컨테이너로 띄울 때는 다릅니다. `docker-compose.deploy.yml`이 `.env`를 `env_file`로 주입하므로 그 안의 값이 실제 환경변수가 됩니다. 다만 같은 파일의 `environment`가 `SPRING_PROFILES_ACTIVE`를 `local`로 다시 덮어쓰고 있어, 지금은 `.env`에 `prod`를 적어도 `local`로 뜹니다([deploy.md](../deploy.md#주의할-점)).

## DB 스키마 (Flyway)

| 설정 | 값 | 위치 |
|---|---|---|
| 마이그레이션 위치 | `src/main/resources/db/migration/` (`V1`~`V24`) | — |
| `baseline-on-migrate` | `true` | `application.yml` |
| `baseline-version` | `0` | `application.yml` |
| JPA `ddl-auto` | `none` | `application-local.yml`, `application-test.yml` |

애플리케이션을 기동하면 Flyway가 마이그레이션을 적용합니다. 적용 이력은 `flyway_schema_history` 테이블에 남습니다. 작성 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md#db-마이그레이션-flyway)를 참고하세요.

| 버전 | 내용 |
|---|---|
| `V1`, `V2` | `vector`, `postgis` extension 생성 |
| `V3` | `faq_category`, `faq`(1024차원 벡터, HNSW cosine 인덱스), `faq_old`, `question_log`, `faq_log` |
| `V4` | `stores`(PostGIS `geography` 자동 생성 컬럼, GIST 인덱스), `service_types`, `store_services` |
| `V5` | 임시 매장·서비스 유형 데이터 |
| `V6` | DB 타임존 `Asia/Seoul` |
| `V7` | `faq_old` → `old_faq`, 작성자 컬럼명 `created_by`/`updated_by` |
| `V8` | `faq_log.rank`를 INTEGER로, `faq_log`·`faq_category`에 `created_at` |
| `V9` | `users`(활성 사용자 이메일 부분 UNIQUE), 기존 테이블의 사용자 FK |
| `V10` | `refresh_tokens`, `faq_category.updated_at` |
| `V11` | `faq_category`, `service_types` soft delete와 활성 행 기준 UNIQUE |
| `V12` | `question_log.llm_question`(생성 답변), `answer_attempts_history` |
| `V13` | `faq`, `old_faq`에 `intent` (`GENERAL`/`STORE_DATA`/`USER_DATA`, 기본 `GENERAL`) |
| `V14` | `forbidden_words` (`ACTIVE`/`INACTIVE`) |
| `V15` | `unanswered_question_groups`(대표 질문, 중심 벡터, 질문 수, 처리 상태 `PENDING`/`APPROVED`/`ON_HOLD`/`REJECTED`), `unanswered_questions`(질문 벡터, 원인 `NO_FAQ`/`INSUFFICIENT_FAQ`, 가장 가까운 FAQ) |
| `V23` | `embedding_profiles`(엔진·모델 이름·차원·버전의 조합), `faq_embeddings`(Profile별 FAQ 벡터, HNSW cosine 인덱스), `unanswered_question_embeddings`, `unanswered_group_embeddings`(Profile별 미응답 질문 벡터와 묶음 중심 벡터) |

표에 없는 버전(`V16`~`V22`, `V24`)은 각 SQL 파일을 참고하세요. FAQ와 관리자 계정은 마이그레이션에 포함되어 있지 않습니다. 로컬에서는 `tools/reset-local-db.ps1`이 기본 계정과 FAQ 1,000건을 넣습니다([안내](../how-to/reset-local-db.md)).

`V23` 이후에도 기존 벡터 컬럼(`faq.vector`, `unanswered_questions.question_vector`, `unanswered_question_groups.centroid`)은 남아 있습니다. FAQ 검색과 저장은 `faq_embeddings`만 쓰고 `faq.vector`는 읽지도 쓰지도 않습니다. 미응답 쪽 두 컬럼은 `NOT NULL`이라 새 테이블과 함께 같은 값을 씁니다.

## Ollama 관련 설정 두 곳

같은 환경변수를 서로 다른 설정 키가 각각 읽습니다.

| 설정 키 | 읽는 주체 | 용도 |
|---|---|---|
| `spring.ai.ollama.*` | Spring AI 자동 구성, `LlmConfig` | LLM 채팅. 서비스 호출에는 `LlmConfig`의 전용 모델 인스턴스 사용. 답변 생성 엔진이 `ollama`일 때만 사용 |
| `ollama.*` | `EmbeddingConfig` (`OllamaEmbeddingClient`) | 임베딩 생성 (`/api/embed` 직접 호출). 임베딩 엔진이 `ollama`일 때만 사용 |

`ollama.*`에는 `base-url`, `embedding.model`, `connect-timeout`, `read-timeout`이 있습니다. 왜 이렇게 나뉘어 있는지는 [architecture.md](../architecture.md#spring-ai를-쓰는-범위)를 참고하세요.

`application-prod.yml`에는 `ollama.*`만 있고 `spring.ai.ollama.*`가 없습니다. 그래서 `prod` 프로필에서 답변 생성 엔진을 `ollama`로 쓰면 `OLLAMA_BASE_URL`이 아니라 `LlmConfig`의 기본값 `http://localhost:11435`를 호출합니다. `prod`에서 Ollama로 답변을 만들려면 이 키를 추가해야 합니다.

## Spring AI 현재 상태

| 설정 | `local` | `prod` | `test` |
|---|---|---|---|
| `spring.ai.model.chat` | `ollama` | `none` | `none` |
| `spring.ai.model.embedding` | `none` | `none` | `none` |
| `spring.ai.vectorstore.type` | `none` | `none` | 지정하지 않음 (pgvector 사용) |
| pgvector 차원 / 인덱스 / 거리 | 주석 처리됨 | 없음 | `1024` / `HNSW` / `COSINE_DISTANCE` |
| pgvector `initialize-schema` | 주석 처리됨 | 없음 | `true` |

- `spring.ai.model.chat`은 Spring AI가 자동으로 만드는 채팅 모델 빈을 정합니다. 서비스가 실제로 호출하는 클라이언트는 이 값과 관계없이 `LlmConfig`가 `AI_MODE`에 따라 만듭니다.
- `local`과 `prod`에서는 Spring AI의 `PgVectorStore`를 쓰지 않습니다. 임베딩과 검색은 `EmbeddingService`와 `FaqVectorRepository`가 직접 처리합니다.
- `test`에서는 테스트 전용 임베딩 구현을 등록해 `PgVectorStore`가 만들어지고, 그 스키마(1024·HNSW·cosine)와 저장·검색을 검증합니다.
- 따라서 **pgvector 관련 설정값을 검증하는 것은 테스트 프로필뿐**입니다. Spring AI 벡터 스토어를 정식 채택하면 `local`의 주석을 되살리고 두 프로필을 맞춰야 합니다.

JPA의 `ddl-auto: none`은 JPA 테이블 자동 생성을 끄는 설정입니다. 별도의 Spring AI pgvector `initialize-schema: true`까지 끄는 것은 아닙니다.

## 기타 고정 설정값

| 설정 | 값 | 파일 |
|---|---|---|
| Actuator 노출 endpoint | `health`만 | `application.yml` |
| Health 상세 표시 | `local`은 `always`, `prod`는 `never` | `application-local.yml`, `application-prod.yml` |
| 인증 없이 호출 가능한 경로, `ADMIN` 경로 | [architecture.md#인증](../architecture.md#인증) | `SecurityConfig` |
| 채팅 질문 최대 길이 | 4000자 | `ChatRequestDto`, `ChatService` |
| 채팅 답변 생성 Executor | 가상 스레드, 동시 처리 수 제한 없음, 종료 대기 150초 | `AsyncConfig` |
| Ollama 임베딩 요청 옵션 | `num_ctx: 4096`, `keep_alive: 30m` | `OllamaEmbeddingClient` |
| 임베딩 차원 | 1024 (DB 컬럼 `vector(1024)`와 같음). 다른 차원의 응답은 거절 | `EmbeddingClient` |
| Nginx 프록시 응답 제한 시간 | `180s` | `infra/nginx/nginx.conf` |
| Nginx `/chat/`·`/api/chat/` 게스트 요청 속도 제한 (IP별, `Authorization: Bearer …`가 없는 요청) | `CHAT_GUEST_RATE_LIMIT_RATE`(기본 `30r/m`), `CHAT_GUEST_RATE_LIMIT_BURST`(기본 `10`) | `.env`, `docker-compose.deploy.yml`, `infra/nginx/nginx.conf` |
| 테스트 타임존 | `Asia/Seoul` | `build.gradle` |
| 테스트 JVM 최대 힙 | `1024m` | `build.gradle` |
| DB 타임존 | `V6__set_database_timezone.sql` | 마이그레이션 |

`CHAT_RESPONSE_TIMEOUT_MILLIS`를 바꿀 때는 Nginx 제한 시간과의 관계를 [deploy.md](../deploy.md#주의할-점)에서 확인하세요.

## 자동 테스트 구성

- 이미지: `infra/postgres/Dockerfile`(PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4). Compose와 Testcontainers가 같은 Dockerfile을 사용합니다. `build.gradle`이 `infra/postgres`를 테스트 리소스로 등록합니다.
- 테스트 DB: `ubot_test`. 사용자 `ubot_test`, 비밀번호는 실행마다 임의 생성. 호스트 포트는 Testcontainers가 할당하고 `@ServiceConnection`으로 Spring에 연결합니다.
- 개발 DB와 볼륨을 공유하지 않고 컨테이너를 재사용하지 않습니다. 테스트 클래스가 끝나면 컨텍스트와 컨테이너를 정리합니다.
- 사전 요구사항은 JDK 17 이상과 실행 중인 Docker입니다. Java 21 toolchain은 없으면 Gradle이 자동으로 내려받습니다. `.env`와 개발 Compose는 필요하지 않습니다.
- `application-test.yml`은 JWT 비밀 키, `kakao.local.api-key: test-key`, 임베딩 설정(`ollama.base-url: http://localhost:11434` 등)을 테스트 전용 값으로 고정합니다. `AI_MODE`는 지정하지 않으므로 기본값 `ollama`로 동작합니다.
- 테스트 JVM의 최대 힙은 `1024m`입니다. Gradle 기본값(512MB)으로는 전체 테스트를 한 번에 돌릴 때 `Java heap space`로 실패했습니다.
- `tools/ci.ps1`은 CI와 같은 명령(`gradlew clean build --no-daemon --console=plain`)을 로컬에서 실행합니다.
- 예외적으로 `IntentClassificationAnalysis`는 `@TestPropertySource`로 Ollama 주소를 `http://localhost:11435`로 바꿔 실제 Ollama를 호출합니다([troubleshooting](../troubleshooting.md#로컬-테스트에서-intentclassificationanalysis가-실패함)).

## LLM 호출 모듈 설정

`LlmConfig`는 답변 생성 엔진에 따라 `LlmClient` 구현체 하나만 등록합니다. 엔진은 `AI_MODE`가 `ollama`·`vllm`이면 그 값이고, `custom`이면 `AI_CHAT_ENGINE`입니다. 클라이언트는 `LlmClientFactory`가 만들고, 두 엔진 모두 같은 `LLM_CONNECT_TIMEOUT`, `LLM_READ_TIMEOUT`을 씁니다.

| 엔진 | 구현체 | 주소 | 모델 이름 |
|---|---|---|---|
| `ollama` | `OllamaClient` (Spring AI `OllamaChatModel`) | `spring.ai.ollama.base-url` (`OLLAMA_BASE_URL`) | `spring.ai.ollama.chat.options.model`이 있으면 그 값, 없으면 `OLLAMA_CHAT_MODEL` |
| `vllm` | `OpenAiCompatibleLlmClient` | `LLM_BASE_URL` | `LLM_MODEL` |

- 모듈을 만들 때는 외부 서버에 접속하지 않습니다. 모델을 자동으로 내려받거나 요청을 자동으로 재시도하지도 않습니다.
- Ollama에서 모델 이름이 비어 있으면 기동은 되고, 실제 호출 때 `LLM_MODEL_NOT_CONFIGURED`(`LLM-002`) 오류를 반환합니다. 사용할 모델을 Ollama에 미리 준비하고 모델 이름을 설정하세요.
- 답변 시도 기록(`answer_attempts_history.llm_model`)에는 선택된 엔진의 모델 이름이 남습니다. `ollama`면 `OLLAMA_CHAT_MODEL`, `vllm`이면 `LLM_MODEL` 값입니다.

자세한 내용은 [LLM 모듈 안내](../how-to/llm-module.md#ai-실행-엔진-선택)에 있습니다.

## 임베딩 호출 모듈 설정

`EmbeddingConfig`는 임베딩 엔진에 따라 `EmbeddingClient` 구현체 하나만 등록합니다. 엔진은 `AI_MODE`가 `ollama`·`vllm`이면 그 값이고, `custom`이면 `AI_EMBEDDING_ENGINE`입니다. 클라이언트는 `EmbeddingClientFactory`가 만듭니다.

| 엔진 | 구현체 | 호출 | 모델 이름 | 제한 시간 |
|---|---|---|---|---|
| `ollama` | `OllamaEmbeddingClient` | `ollama.base-url`(`OLLAMA_BASE_URL`)의 `/api/embed` | `OLLAMA_EMBEDDING_MODEL` | `OLLAMA_CONNECT_TIMEOUT`, `OLLAMA_READ_TIMEOUT` |
| `vllm` | `OpenAiCompatibleEmbeddingClient` | `EMBEDDING_BASE_URL`의 `/embeddings` | `EMBEDDING_MODEL` | `EMBEDDING_CONNECT_TIMEOUT`, `EMBEDDING_READ_TIMEOUT` |

- 모듈을 만들 때는 외부 서버에 접속하지 않습니다.
- 두 구현체 모두 응답이 1024차원이 아니거나 유한하지 않은 값이 있으면 `EM-002`로 거절합니다.
- 답변 시도 기록(`answer_attempts_history.embedding_model`)에는 선택된 엔진의 모델 이름이 남습니다.

벡터를 저장하거나 검색할 때는 `EmbeddingProfileService`가 지금 설정에 해당하는 Embedding Profile을 찾아 그 Profile의 벡터만 다룹니다. Profile은 아래 네 값의 조합이고, 없으면 처음 쓸 때 `embedding_profiles`에 만들어집니다.

| 값 | 출처 |
|---|---|
| `provider` | 임베딩 엔진이 `ollama`면 `ollama`, `vllm`이면 `openai-compatible` |
| `model_name` | 위 표의 모델 이름 |
| `dimensions` | `1024` (고정) |
| `profile_version` | `EMBEDDING_PROFILE_VERSION` |

이 중 하나라도 바뀌면 다른 Profile이 되어 기존 벡터가 검색에 쓰이지 않습니다. 그때는 백필로 새 Profile의 벡터를 채웁니다. 절차는 [LLM 모듈 안내](../how-to/llm-module.md#백필)에 있습니다.

현재 구현은 채팅에서 검색한 FAQ와 원래 질문을 `AiService` → `PromptService` → `LlmService`로
전달하고, 완성된 답변을 한 번에 반환합니다. 기본 프롬프트 파일인 `prompts/faq-system.txt`,
`prompts/faq-user.txt`에는 본문이 들어 있습니다. 파일이 없거나 비어 있거나 자리표시자가 빠지면
모델을 호출하지 않고 `CHAT-014`로 실패합니다. 외부 프롬프트 파일을 사용하려면
`prompt.faq.system-location`, `prompt.faq.user-location`에 Spring `file:` 리소스 경로를 지정합니다.
호출 계약과 테스트 방법은 [LLM 모듈 연결 안내](../how-to/llm-module.md)를 참고하세요.
