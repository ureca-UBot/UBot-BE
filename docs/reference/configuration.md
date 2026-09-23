# 설정 Reference

> 문서 기준 시점: 2026-09-22 (`develop` = `04c5a6c`, LLM 채팅 연결 반영)

## 설정 파일

```text
src/main/resources/
├── application.yml         # 모든 환경 공통 (Flyway, JWT, actuator)
├── application-local.yml   # 로컬 개발 (기본 프로필)
└── application-test.yml    # 로컬·CI의 자동 테스트
```

| 프로필 | 사용 환경 | 활성화 방법 | 접속 정보 공급원 |
|---|---|---|---|
| `local` | 개발자 PC (`bootRun`, Eclipse) | 기본값 (`spring.profiles.default: local`) | 프로젝트 루트의 `.env` 또는 OS 환경변수 |
| `test` | 로컬·GitHub Actions의 자동 테스트 | `gradlew test`는 `build.gradle`에서 `spring.profiles.active=test`를 강제. IDE에서 JUnit을 직접 실행하면 테스트 클래스의 `@ActiveProfiles("test")`가 적용 | Testcontainers + `@ServiceConnection` |

자동 테스트는 별도 DB 컨테이너의 임의 호스트 포트와 접속 정보를 사용합니다. 개발 `.env`, 고정 포트, 개발 DB·볼륨을 공유하지 않습니다. `test` 프로필만 지정해 `bootRun`을 실행하는 것은 Testcontainers 기반 테스트 실행과 다릅니다.

## 개발 환경변수

`.env.example`을 복사했을 때의 값과 Spring YAML의 fallback을 구분합니다. fallback이 "없음"인 값은 `.env` 또는 OS 환경변수로 반드시 제공해야 하며, 없으면 기동 시점에 `Could not resolve placeholder` 오류가 납니다.

| 변수 | 사용처 | `.env.example` 값 | Spring fallback |
|---|---|---|---|
| `POSTGRES_HOST` | Spring | `localhost` | `localhost` |
| `POSTGRES_PORT` | Docker, Spring | `15432` | `15432` |
| `POSTGRES_DB` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_USER` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_PASSWORD` | Docker, Spring | `change-me` (**변경 필수**) | 없음 |
| `OLLAMA_PORT` | Docker의 호스트 포트 | `11435` | Spring에서 읽지 않음 |
| `OLLAMA_BASE_URL` | Spring (`spring.ai.ollama`, `ollama`) | `http://localhost:11435` | `http://localhost:11435` |
| `OLLAMA_EMBEDDING_MODEL` | Spring, `ollama-init` | `bge-m3:567m` | `bge-m3:567m` |
| `OLLAMA_CHAT_MODEL` | Spring (`spring.ai.ollama.chat.model`), `LlmConfig` | 빈 값 (사용할 모델 지정) | YAML에는 없음. `LlmConfig`는 빈 값 허용 |
| `LLM_CONNECT_TIMEOUT` | LLM 전용 HTTP 연결 제한 시간 | `3s` | `LlmConfig` 기본값 `3s` |
| `LLM_READ_TIMEOUT` | LLM 전용 HTTP 응답 제한 시간 | `120s` | `LlmConfig` 기본값 `120s` |
| `OLLAMA_CONNECT_TIMEOUT` | `EmbeddingService` | `3s` | `3s` |
| `OLLAMA_READ_TIMEOUT` | `EmbeddingService` | `10s` | `10s` |
| `KAKAO_REST_API_KEY` | `KakaoLocalClient` | 빈 값 | 없음 |
| `JWT_SECRET` | `JwtUtil` | 안내 문구 (**변경 필수**) | 없음 |
| `JWT_ACCESS_TOKEN_EXPIRATION_MILLIS` | `JwtUtil` | `600000` (10분) | `3600000` (1시간) |
| `JWT_REFRESH_TOKEN_EXPIRATION_DAYS` | `RefreshTokenService` | `14` | `14` |
| `SPRING_PROFILES_ACTIVE` | OS 환경변수로 제공하면 프로필 선택 | `local` | `.env`에 적는 것만으로는 프로필을 바꾸지 못함 |

Compose는 PostgreSQL `127.0.0.1:15432 → 5432`, Ollama `127.0.0.1:11435 → 11434`로 노출합니다. `OLLAMA_PORT`를 바꾸면 Spring이 사용하는 `OLLAMA_BASE_URL`의 포트도 함께 바꿔야 합니다.

### 키가 있지만 값이 비어 있을 때

`OLLAMA_CHAT_MODEL`과 `KAKAO_REST_API_KEY`는 `.env.example`에서 빈 값입니다. 빈 값이면 기동은 되지만 해당 기능이 실패합니다(채팅 모델 미지정, 카카오 API 인증 실패). 키 줄 자체를 지우면 기동이 실패합니다.

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

## DB 스키마 (Flyway)

| 설정 | 값 | 위치 |
|---|---|---|
| 마이그레이션 위치 | `src/main/resources/db/migration/` (`V1`~`V11`) | — |
| `baseline-on-migrate` | `true` | `application.yml` |
| `baseline-version` | `0` | `application.yml` |
| JPA `ddl-auto` | `none` | `application-local.yml`, `application-test.yml` |

애플리케이션을 기동하면 Flyway가 마이그레이션을 적용합니다. `V1`이 `vector`, `V2`가 `postgis` extension을 생성하고 이후 파일들이 FAQ·매장·사용자·토큰 테이블을 만듭니다. 적용 이력은 `flyway_schema_history` 테이블에 남습니다. 작성 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md#db-마이그레이션-flyway)를 참고하세요.

## Ollama 관련 설정 두 곳

같은 환경변수를 서로 다른 설정 키가 각각 읽습니다.

| 설정 키 | 읽는 주체 | 용도 |
|---|---|---|
| `spring.ai.ollama.*` | Spring AI 자동 구성, `LlmConfig` | LLM 채팅. 서비스 호출에는 `LlmConfig`의 전용 모델 인스턴스 사용 |
| `ollama.*` | `EmbeddingService` | 임베딩 생성 (`/api/embed` 직접 호출) |

`ollama.*`에는 `base-url`, `embedding.model`, `connect-timeout`, `read-timeout`이 있습니다. 왜 이렇게 나뉘어 있는지는 [architecture.md](../architecture.md#spring-ai를-쓰는-범위)를 참고하세요.

## Spring AI 현재 상태

| 설정 | `local` | `test` |
|---|---|---|
| `spring.ai.model.chat` | `ollama` | `none` |
| `spring.ai.model.embedding` | `none` | `none` |
| `spring.ai.vectorstore.type` | `none` | 지정하지 않음 (pgvector 사용) |
| pgvector 차원 / 인덱스 / 거리 | 주석 처리됨 | `1024` / `HNSW` / `COSINE_DISTANCE` |
| pgvector `initialize-schema` | 주석 처리됨 | `true` |

- `local`에서는 Spring AI의 `PgVectorStore`를 쓰지 않습니다. 임베딩과 검색은 `EmbeddingService`와 `FaqVectorRepository`가 직접 처리합니다.
- `test`에서는 테스트 전용 임베딩 구현을 등록해 `PgVectorStore`가 만들어지고, 그 스키마(1024·HNSW·cosine)와 저장·검색을 검증합니다.
- 따라서 **pgvector 관련 설정값을 검증하는 것은 테스트 프로필뿐**입니다. Spring AI 벡터 스토어를 정식 채택하면 `local`의 주석을 되살리고 두 프로필을 맞춰야 합니다.

JPA의 `ddl-auto: none`은 JPA 테이블 자동 생성을 끄는 설정입니다. 별도의 Spring AI pgvector `initialize-schema: true`까지 끄는 것은 아닙니다.

## 기타 고정 설정값

| 설정 | 값 | 파일 |
|---|---|---|
| Actuator 노출 endpoint | `health`만 | `application.yml` |
| Health 상세 표시 | `always` | `application-local.yml` |
| 인증 없이 호출 가능한 경로 | `/auth/login`, `/auth/signup`, `/auth/refresh`, `/actuator/health` | `SecurityConfig` |
| 테스트 타임존 | `Asia/Seoul` | `build.gradle` |
| DB 타임존 | `V6__set_database_timezone.sql` | 마이그레이션 |

## 자동 테스트 구성

- 이미지: `infra/postgres/Dockerfile`(PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4). Compose와 Testcontainers가 같은 Dockerfile을 사용합니다. `build.gradle`이 `infra/postgres`를 테스트 리소스로 등록합니다.
- 테스트 DB: `ubot_test`. 사용자 `ubot_test`, 비밀번호는 실행마다 임의 생성. 호스트 포트는 Testcontainers가 할당하고 `@ServiceConnection`으로 Spring에 연결합니다.
- 개발 DB와 볼륨을 공유하지 않고 컨테이너를 재사용하지 않습니다. 테스트 클래스가 끝나면 컨텍스트와 컨테이너를 정리합니다.
- 사전 요구사항은 JDK 17 이상과 실행 중인 Docker입니다. Java 21 toolchain은 없으면 Gradle이 자동으로 내려받습니다. `.env`, 개발 Compose, Ollama 모델은 필요하지 않습니다.

## LLM 호출 모듈 설정

`LlmConfig`는 기존 `spring.ai.ollama.base-url`과 `OLLAMA_CHAT_MODEL`을 사용해
LLM 호출 전용 Spring AI `OllamaChatModel`을 구성합니다. `spring.ai.ollama.chat.options.model`을
명시한 경우에는 그 값이 `OLLAMA_CHAT_MODEL`보다 우선합니다. `application-local.yml`의
`spring.ai.ollama.chat.model` 대신 이 우선순위에 따라 모델명을 읽습니다. 기존 임베딩 클라이언트와
Spring AI 자동 구성 빈을 수정하지 않고, LLM의 연결·응답 제한 시간만 별도로 적용합니다.

모델명이 비어 있어도 모듈 생성 시 외부 서버에 접속하지 않습니다. 실제 호출 시에는
`LLM_MODEL_NOT_CONFIGURED` 오류를 반환합니다. 사용할 모델을 Ollama에 미리 준비하고
모델명을 설정하세요. 이 모듈은 모델을 자동 다운로드하거나 요청을 자동 재시도하지 않습니다.

현재 구현은 채팅에서 검색한 FAQ와 원래 질문을 `AiService` → `PromptService` → `LlmService`로
전달하고, 완성된 답변을 한 번에 반환합니다. 기본 프롬프트 파일인 `prompts/faq-system.txt`,
`prompts/faq-user.txt`는 담당자의 최종 본문을 기다리며 비워 두었습니다. 파일이 준비되지 않으면
모델을 호출하지 않고 채팅 실패 응답을 반환합니다. 외부 프롬프트 파일을 사용하려면
`prompt.faq.system-location`, `prompt.faq.user-location`에 Spring `file:` 리소스 경로를 지정합니다.
호출 계약과 테스트 방법은 [LLM 모듈 연결 안내](../how-to/llm-module.md)를 참고하세요.
