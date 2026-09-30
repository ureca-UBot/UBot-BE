# Troubleshooting

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

해결 후에는 항상 [quickstart 6단계](quickstart.md#6-동작-확인)의 health check를 다시 통과하는지 확인하세요.

## password authentication failed

**증상**

```text
FATAL: password authentication failed for user "ubot"
```

**원인**

`POSTGRES_PASSWORD`는 DB 볼륨이 **처음 생성될 때만** 적용됩니다. 컨테이너를 한 번 띄운 뒤 `.env`의 비밀번호를 바꾸면 DB와 `.env`의 값이 달라집니다.

또한 현재 `.env`를 Compose와 Spring이 서로 다른 문법으로 읽습니다. 한글·따옴표·이스케이프 등이 있으면 같은 파일에서도 서로 다른 비밀번호가 될 수 있습니다. 먼저 [파싱 제약](reference/configuration.md#env-파싱-제약-아직-미해결)을 확인하세요. 이 파싱 문제는 아직 근본 해결하지 않았습니다.

**해결 (데이터 유지)**

`.env`의 비밀번호를 따옴표 없는 긴 임의의 영문·숫자 값으로 정하고, DB에도 같은 비밀번호를 적용합니다. 비밀번호가 셸 명령 기록에 남지 않도록 psql의 대화형 비밀번호 변경을 사용합니다.

```powershell
docker compose exec postgres psql -U ubot -d ubot
```

psql 안에서:

```text
\password ubot
\q
```

프롬프트에서 새 비밀번호를 두 번 입력합니다. 사용자·DB 이름을 바꿨다면 접속 명령과 `\password`의 사용자도 맞춰 바꾸세요. 이후 애플리케이션을 다시 시작합니다.

**해결 (데이터 초기화)**

데이터를 유지해야 하면 위 방법을 사용하세요. 아래 명령은 **PostgreSQL 데이터와 Ollama 다운로드 모델이 담긴 Compose 볼륨을 모두 삭제**합니다. 백업과 삭제 범위를 확인하고 전체 개발 환경을 초기화하려는 경우에만 사용합니다. Testcontainers 테스트 DB 정리에는 필요하지 않습니다.

```powershell
docker compose down -v
docker compose up -d
```

## DecodingException: Illegal base64 character

**증상**

```text
Error creating bean with name 'jwtUtil' ... Constructor threw exception
Caused by: io.jsonwebtoken.io.DecodingException: Illegal base64 character: '-'
```

**원인**

`.env`의 `JWT_SECRET`이 `.env.example`의 기본값(안내 문구) 그대로입니다. `JwtUtil`은 기동 시점에 이 값을 Base64로 디코딩하므로 애플리케이션이 뜨지 않습니다.

**해결**

Base64로 인코딩된 32바이트 이상의 값을 만들어 `.env`의 `JWT_SECRET`에 넣습니다.

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

macOS/Linux에서는 `openssl rand -base64 32`를 사용합니다. 값을 넣은 뒤 애플리케이션을 다시 시작합니다. 키 길이가 32바이트보다 짧으면 `WeakKeyException`이 발생합니다.

## Could not resolve placeholder 'POSTGRES_PASSWORD'

**원인**

Spring이 `.env`를 찾지 못했습니다.

**확인할 것**

1. 프로젝트 루트에 `.env` 파일이 있는지 (`.env.example`만 있으면 안 됨)
2. `.env`에 `POSTGRES_PASSWORD=` 줄이 있는지
3. 프로젝트 루트에서 실행했는지. Eclipse라면 Run Configuration → Arguments → Working directory가 프로젝트 루트인지

## 15432 또는 11435 포트가 이미 사용 중

**증상**

`docker compose up` 시 `port is already allocated` 또는 `bind: address already in use`

**원인**

다른 애플리케이션이나 Compose 프로젝트가 호스트 포트를 사용하고 있을 수 있습니다. 이 프로젝트의 `.env.example`은 PostgreSQL `15432`, Ollama `11435`를 사용합니다.

**해결**

PostgreSQL은 `.env`의 `POSTGRES_PORT`를 바꿉니다. Spring도 같은 변수를 읽습니다.

```dotenv
POSTGRES_PORT=15433
```

Ollama 포트를 바꾸는 경우에는 두 값을 함께 맞춥니다.

```dotenv
OLLAMA_PORT=11436
OLLAMA_BASE_URL=http://localhost:11436
```

```powershell
docker compose up -d
```

애플리케이션도 새 설정으로 다시 시작합니다. 컨테이너 내부 포트 `5432`, `11434`를 바꾸는 작업은 아닙니다.

## 컨테이너가 healthy가 되지 않음

```powershell
docker compose logs --tail=100 postgres ollama
```

- `Cannot connect to the Docker daemon` → Docker Desktop이 실행 중인지 확인
- `.env` 관련 경고(`variable is not set`) → 프로젝트 루트에 `.env`가 있는지 확인

## vector 또는 postgis extension이 없음

**원인**

DB 이미지에는 extension 실행 파일만 설치됩니다. DB별 `vector`와 `postgis` 생성은 애플리케이션 시작 시 Flyway V1·V2가 담당합니다. PostgreSQL 컨테이너의 `healthy`는 서버 접속 가능 상태만 의미하므로, 애플리케이션을 아직 실행하지 않았거나 Flyway가 실패했다면 extension이 없을 수 있습니다.

**해결 (데이터 유지)**

기본 사용자·DB 이름 기준입니다. `.env`에서 바꿨다면 명령도 맞춥니다.

```powershell
docker compose exec postgres psql -U ubot -d ubot -c "SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'postgis') ORDER BY extname;"
docker compose exec postgres psql -U ubot -d ubot -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

`flyway_schema_history`가 없거나 V1·V2가 보이지 않으면 DB 이미지를 갱신하고 애플리케이션을 다시 실행해 Flyway 로그를 확인합니다. DB나 볼륨을 삭제할 필요가 없습니다.

```powershell
docker compose up -d --build postgres
.\gradlew.bat bootRun
```

마이그레이션이 실패하면 로그의 첫 SQL 오류를 해결한 뒤 애플리케이션을 다시 시작합니다. 적용된 마이그레이션 파일을 수정하거나 psql에서 extension을 임의로 생성·삭제하지 않습니다. 변경이 필요하면 [Flyway 규칙](../CONTRIBUTING.md#db-마이그레이션-flyway)에 따라 새 마이그레이션을 추가합니다.

## Ollama 모델이 준비되지 않음

이 프로젝트는 Compose의 `ollama-init`이 Compose의 Ollama에 `bge-m3:567m`을 받습니다. 호스트에서 실행한 `ollama list`는 다른 Ollama 설치를 가리킬 수 있습니다.

```powershell
docker compose ps -a ollama ollama-init
docker compose logs --tail=100 ollama-init
docker compose exec ollama ollama list
```

`ollama-init`이 종료 코드 0으로 끝났는지와 `.env`에 지정한 모델이 실제 목록에 있는지 확인합니다. 다운로드가 실패했다면 네트워크·디스크 여유를 확인한 뒤 다시 실행합니다.

```powershell
docker compose up -d ollama
docker compose run --rm ollama-init
```

Spring의 `OLLAMA_BASE_URL`이 `OLLAMA_PORT`와 맞는지도 확인하세요. 기본은 `http://localhost:11435`입니다.

## 테스트에서 Docker를 찾지 못함

자동 테스트는 Testcontainers가 전용 PostgreSQL + pgvector + PostGIS 환경을 빌드하고 컨테이너를 생성하므로 Docker 엔진에 접근할 수 있어야 합니다.

```powershell
docker info
.\gradlew.bat test --rerun-tasks
```

Docker Desktop의 Linux 컨테이너 엔진이 실행 중인지 확인합니다. 첫 실행의 이미지 다운로드 실패라면 네트워크·레지스트리 접근도 확인하세요. 개발 `.env`를 복사하거나 개발용 `ubot_test` DB를 수동 생성하는 것으로 해결하지 않습니다. 테스트는 개발 DB 없이 실행되도록 분리되어 있습니다.

## 로컬 테스트에서 IntentClassificationAnalysis가 실패함

**원인**

`embedding/analysis/IntentClassificationAnalysis`는 threshold 측정용 분석 테스트로, 다른 테스트와 달리 `http://localhost:11435`의 실제 Ollama에 `bge-m3:567m` 임베딩을 요청합니다. `CI=true` 환경변수가 있을 때만 건너뛰므로, 로컬에서 Ollama가 꺼져 있으면 `gradlew test` 전체가 이 테스트 때문에 실패할 수 있습니다.

**해결**

분석이 필요하면 Compose의 Ollama를 띄우고 모델을 준비한 뒤 실행합니다([quickstart 4단계](quickstart.md#4-embedding-모델-준비-확인)). 분석이 필요 없으면 건너뜁니다.

```powershell
$env:CI = 'true'; .\gradlew.bat test
```

## 채팅 요청이 실패함

`POST /chat/questions`가 실패하면 응답의 `code`로 원인을 구분합니다. 코드별 의미와 HTTP 상태는 [오류 코드 Reference](reference/error-codes.md#채팅)에 있고, 아래는 코드별로 확인할 곳입니다. 실패 이력은 [`answer_attempts_history`](how-to/db-access.md#주요-테이블)에서도 볼 수 있습니다.

| code | 확인할 것 |
|---|---|
| `FW-003` | 관리자 금지어 목록 |
| `G-001` | 요청 본문 `question`이 비었거나 4000자를 넘는지 |
| `AUTH-001`, `JWT-*` | `Authorization: Bearer <token>` 헤더, 토큰 만료 여부 |
| `CHAT-011`, `CHAT-003` | `Idempotency-Key`가 소문자 16진수 64자리인지, 같은 사용자의 토큰으로 재시도했는지 |
| `CHAT-004`~`CHAT-007` | 이전 응답의 `status`, `attemptCount` |
| `EM-001`, `EM-003` | Ollama 실행 여부, `OLLAMA_BASE_URL`, [모델 준비](#ollama-모델이-준비되지-않음) |
| `CHAT-010`, `CHAT-008` | PostgreSQL 상태, 애플리케이션 로그 |
| `CHAT-012` | `faq`에 삭제되지 않은 FAQ가 있는지 ([FAQ 준비](how-to/local-data.md#2-faq-등록)) |
| `CHAT-013` | `CHAT_CONFIDENCE_THRESHOLD`, FAQ의 `vector`가 비어 있지 않은지. `CHAT-012`·`CHAT-013`으로 끝난 질문은 `unanswered_questions`에 저장됩니다 |
| `CHAT-014` | `prompts/faq-*.txt`, `prompt.faq.*-location` |
| `LLM-002` | `.env`의 `OLLAMA_CHAT_MODEL` |
| `LLM-003` | `docker compose exec ollama ollama list`에 채팅 모델이 있는지 |
| `LLM-004`, `CHAT-016` | `LLM_READ_TIMEOUT`, `CHAT_RESPONSE_TIMEOUT_MILLIS`, 모델 크기·GPU 사용 여부 |
| `LLM-005` | 모델 설정, 애플리케이션 로그 |

FAQ를 SQL로 직접 넣었다면 `vector`가 비어 있어 유사도가 0으로 계산됩니다. 관리자 API로 등록하거나 질문을 바꿔 수정해 임베딩을 다시 만드세요. 질문을 바꾸지 않고 수정하면 임베딩을 다시 만들지 않습니다.

채팅이 성공했는데 `answer`에 JSON 문자열이 그대로 보이는 것은 오류가 아닙니다([LLM 모듈 안내](how-to/llm-module.md#출력-형식과-서버-처리-json-분리는-후속-작업)).

## 관리자 API가 403을 반환함

`AUTH-002`(접근 권한이 없습니다)가 반환됩니다. `/admin/**`은 `ADMIN` 역할이 필요합니다. 회원가입으로 만든 계정은 모두 `USER`입니다. 로컬에서는 [DB에서 역할을 바꿉니다](how-to/db-access.md#로컬에서-관리자-계정-만들기).

## 그래도 해결되지 않으면

아래 정보를 팀 채널에 공유하세요. 비밀번호는 지우고 올립니다.

```powershell
docker compose ps
docker compose logs --tail=100 postgres ollama ollama-init
.\gradlew.bat bootRun --stacktrace
```
