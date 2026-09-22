# 설정 Reference

## 설정 파일

```text
src/main/resources/
├── application.yml         # 모든 환경 공통
├── application-local.yml   # 로컬 개발 (기본 프로필)
└── application-test.yml    # 로컬·CI의 자동 테스트
```

| 프로필 | 사용 환경 | 활성화 방법 | 접속 정보 공급원 |
|---|---|---|---|
| `local` | 개발자 PC (`bootRun`, Eclipse) | 기본값 (`spring.profiles.default: local`) | 프로젝트 루트의 `.env` 또는 OS 환경변수 |
| `test` | 로컬·GitHub Actions의 자동 테스트 | `gradlew test`는 `build.gradle`에서 `spring.profiles.active=test`를 강제. IDE에서 JUnit을 직접 실행하면 테스트 클래스의 `@ActiveProfiles("test")`가 적용 | Testcontainers + `@ServiceConnection` |

자동 테스트는 별도 PostgreSQL 컨테이너의 임의 호스트 포트와 접속 정보를 사용합니다. 개발 `.env`, 고정 포트, 개발 DB·볼륨을 공유하지 않습니다. `test` 프로필만 지정해 `bootRun`을 실행하는 것은 Testcontainers 기반 테스트 실행과 다릅니다. 테스트 DB 구성은 테스트 코드가 제공하므로 `gradlew test`로 실행하세요.

## 개발 환경변수

`.env.example`을 복사했을 때의 값과 Spring YAML의 fallback을 구분합니다. Compose에는 해당 변수의 fallback이 없으므로 `.env` 또는 실행 환경에서 값을 제공해야 합니다.

| 변수 | 사용처 | `.env.example` 값 | Spring YAML fallback |
|---|---|---|---|
| `POSTGRES_HOST` | Spring | `localhost` | `localhost` |
| `POSTGRES_PORT` | Docker, Spring | `15432` | `15432` |
| `POSTGRES_DB` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_USER` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_PASSWORD` | Docker, Spring | `change-me` (**변경 필수**) | 없음 |
| `OLLAMA_PORT` | Docker의 호스트 포트 | `11435` | Spring에서 읽지 않음 |
| `OLLAMA_BASE_URL` | Spring (`local`) | `http://localhost:11435` | `http://localhost:11435` |
| `OLLAMA_EMBEDDING_MODEL` | Spring (`local`), `ollama-init` | `bge-m3:567m` | `bge-m3:567m` |
| `OLLAMA_CHAT_MODEL` | `llm/config/LlmConfig` | 비어 있음 (사용할 모델 지정) | YAML 연결 없이 LLM 설정에서 직접 읽음 |
| `LLM_CONNECT_TIMEOUT` | LLM 전용 HTTP 연결 제한 시간 | `3s` | `LlmConfig` 기본값 `3s` |
| `LLM_READ_TIMEOUT` | LLM 전용 HTTP 응답 제한 시간 | `120s` | `LlmConfig` 기본값 `120s` |
| `SPRING_PROFILES_ACTIVE` | OS 환경변수로 제공하면 프로필 선택 | `local` | `.env`에 적는 것만으로 프로필을 바꾸지 못함 |

Compose는 PostgreSQL `127.0.0.1:15432 → 5432`, Ollama `127.0.0.1:11435 → 11434`로 노출합니다. `OLLAMA_PORT`를 바꾸면 Spring이 사용하는 `OLLAMA_BASE_URL`의 포트도 함께 바꿔야 합니다.

### 값을 읽는 순서

- `local` 프로필은 `spring.config.import: optional:file:.env[.properties]`로 `.env`를 읽습니다.
- OS 환경변수가 `.env`보다 우선합니다. 나중에 실제 환경변수로 주입하면 코드 수정 없이 그 값이 사용됩니다.
- `.env`는 **실행 위치 기준 상대경로**로 찾습니다. 프로젝트 루트가 아닌 곳에서 실행하면 읽지 못합니다.
- Docker Compose도 같은 `.env`를 읽지만 **파싱 문법이 다르므로 같은 값을 읽는다고 보장할 수 없습니다.** 아래 제약을 확인하세요.

### `.env` 파싱 제약 (아직 미해결)

현재 런타임의 읽기 방식은 변경하지 않았습니다. Docker Compose는 dotenv 문법을, Spring은 Java properties 문법을 사용합니다.

- 예를 들어 `POSTGRES_PASSWORD='example'`이면 Compose는 인용부호를 제거하지만 Spring은 인용부호를 비밀번호에 포함합니다.
- Spring Boot 4.1.1의 현재 properties 로딩은 인코딩 미지정 시 ISO-8859-1을 사용하므로 UTF-8로 저장한 한글 등 비ASCII 값이 달라질 수 있습니다.
- `$`, 역슬래시, 따옴표, 공백, 인라인 주석 등도 두 파서에서 의미가 다를 수 있습니다. 단순히 따옴표를 추가하는 것으로 해결되지 않습니다.

당장은 **따옴표 없이 충분히 긴 임의의 영문·숫자 비밀번호**를 사용하고 값 뒤에 주석을 붙이지 않는 방식으로 충돌을 피하세요. 이는 임시 회피책이며, 파싱 방식 통일이나 설정 주입 구조의 근본 해결은 아직 적용하지 않았습니다. [Docker Compose의 dotenv 구문](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#env-file-syntax)도 참고하세요.

### `POSTGRES_PASSWORD`에 기본값이 없는 이유

개발 DB 비밀번호를 코드(Git)에 남기지 않기 위해서입니다. `local`에서 값이 없으면 기동 시점에 `Could not resolve placeholder 'POSTGRES_PASSWORD'` 오류로 실패합니다. 자동 테스트는 개발 비밀번호 대신 Testcontainers의 전용 접속 정보를 사용합니다.

### `SPRING_PROFILES_ACTIVE`가 `.env`에서 효과 없는 이유

`.env`는 `local` 프로필 설정 안에서 읽히므로, 읽는 시점에는 이미 프로필이 결정되어 있습니다. 프로필을 바꾸려면 OS 환경변수나 실행 인자로 지정해야 합니다.

## 자동 테스트 구성

- 이미지: `infra/postgres/Dockerfile`로 PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4 환경을 빌드합니다. Compose와 Testcontainers가 같은 Dockerfile을 사용합니다.
- 테스트 DB: `ubot_test`. 호스트 포트는 Testcontainers가 할당하고 `@ServiceConnection`으로 Spring에 연결합니다.
- `infra/postgres`를 테스트 리소스 디렉터리로 등록해 동일한 Dockerfile을 사용합니다. DB별 extension 생성은 초기화 스크립트가 아니라 Flyway V1·V2가 담당합니다. Eclipse 등 IDE에서는 Gradle 프로젝트를 동기화한 뒤 JUnit 테스트를 실행하세요.
- 개발 DB와 볼륨을 공유하지 않으며 컨테이너 재사용을 하지 않습니다. 테스트 클래스가 끝나면 Spring 컨텍스트와 전용 컨테이너를 정리합니다.
- Ollama chat/embedding 자동 구성을 끄고 테스트 전용 1024차원 임베딩 구현을 사용합니다. Flyway V1·V2, extension 버전, PostGIS 공간 함수와 실제 pgvector 저장·검색을 검증하지만 BGE-M3 모델 품질이나 Ollama 연결은 검증하지 않습니다.
- 사전 요구사항은 JDK 17 이상과 실행 중인 Docker입니다. Java 21 toolchain은 없으면 Gradle이 자동으로 내려받습니다. 첫 실행에는 Gradle 의존성과 이미지 다운로드를 위한 네트워크가 필요할 수 있습니다. `.env`, 개발 Compose, Ollama 모델은 필요하지 않습니다.

## 고정 설정값

| 설정 | 값 | 파일 |
|---|---|---|
| pgvector 차원 | `1024` (개발 환경은 BGE-M3 출력 차원과 일치해야 함) | `application-local.yml`, `application-test.yml` |
| pgvector 인덱스 | `HNSW` | `application-local.yml`, `application-test.yml` |
| 거리 함수 | `COSINE_DISTANCE` | `application-local.yml`, `application-test.yml` |
| pgvector 스키마 초기화 | `true` | `application-local.yml`, `application-test.yml` |
| PostGIS extension | `3.6.4` | `infra/postgres/Dockerfile`, `V2__enable_postgis_extension.sql` |
| JPA `ddl-auto` | `none` | `application-local.yml`, `application-test.yml` |
| Actuator 노출 endpoint | `health`만 | `application.yml` |
| Health 상세 표시 | `always` | `application-local.yml` |

> Embedding 모델을 바꾸면 pgvector 차원도 해당 모델의 출력 차원으로 함께 바꿔야 합니다.

## LLM 호출 모듈 설정

`LlmConfig`는 기존 `spring.ai.ollama.base-url`과 `OLLAMA_CHAT_MODEL`을 사용해
LLM 호출 전용 Spring AI `OllamaChatModel`을 구성합니다. `spring.ai.ollama.chat.options.model`을
명시한 경우에는 그 값이 `OLLAMA_CHAT_MODEL`보다 우선합니다. 기존 임베딩 클라이언트와
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

JPA의 `ddl-auto: none`은 JPA 테이블 자동 생성을 끄는 설정입니다. 별도의 Spring AI pgvector `initialize-schema: true`까지 끄는 것은 아닙니다.
