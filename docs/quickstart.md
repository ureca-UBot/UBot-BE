# 로컬에서 UBot-BE 실행하기

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

## 완료 기준

이 문서를 마치면 `http://localhost:8080/actuator/health`에서 애플리케이션과 DB가 모두 `UP`으로 표시됩니다.

## 요구사항

| 프로그램 | 확인 명령 |
|---|---|
| JDK 17 이상 (Gradle 실행용) | `java -version` |
| Docker Desktop (실행 중이어야 함) | `docker compose version` |
| Git | `git --version` |

Ollama는 Compose 컨테이너로 실행합니다. 호스트에 별도로 설치할 필요가 없습니다.

프로젝트는 Java 21로 빌드합니다. PC에 Java 21이 없으면 첫 `gradlew` 실행 때 Gradle이 자동으로 내려받습니다. 인터넷 연결이 필요하며, 받은 JDK는 사용자 폴더의 `.gradle/jdks`에 저장됩니다. Eclipse에서 직접 실행할 때는 Eclipse에 등록된 JDK를 쓰므로 Java 21 JDK가 등록되어 있어야 합니다.

`.env.example` 기준 호스트 포트 `15432`(PostgreSQL), `8080`(Spring Boot), `11435`(Ollama)가 비어 있어야 합니다. 컨테이너 내부 포트 `5432`, `11434`와 구분하세요.

> 명령은 Windows PowerShell 기준입니다. macOS/Linux에서는 `Copy-Item` 대신 `cp`, `.\gradlew.bat` 대신 `./gradlew`를 사용하세요.

## 1. 저장소 받기

```powershell
git clone https://github.com/ureca-UBot/UBot-BE.git
cd UBot-BE
git switch develop
git pull origin develop
```

개발은 `develop` 브랜치를 기준으로 합니다. 브랜치 규칙은 [CONTRIBUTING.md](../CONTRIBUTING.md)를 참고하세요.

이후 모든 명령은 **프로젝트 루트**(`UBot-BE/`)에서 실행합니다.

## 2. `.env` 만들기

처음 실행할 때만 복사합니다. 기존 `.env`가 있으면 덮어쓰지 말고 필요한 키만 비교하세요.

```powershell
Copy-Item .env.example .env
```

`.env`에서 **반드시 바꿔야 하는 값이 두 개**입니다.

### `POSTGRES_PASSWORD`

충분히 긴 임의의 영문·숫자 값으로 바꿉니다. 따옴표로 감싸지 않습니다.

> 현재 `.env`를 Docker Compose와 Spring이 서로 다른 문법으로 읽습니다. 한글·따옴표·특수문자는 값이 달라질 수 있으며, 영문·숫자로 제한하는 것은 근본 해결이 아닌 임시 회피책입니다. [현재 파싱 제약](reference/configuration.md#env-파싱-제약-아직-미해결)을 확인하세요.

> **다음 단계로 넘어가기 전에 비밀번호를 정하세요.** 이 값은 DB 데이터 디렉터리가 **처음 초기화될 때만** 적용됩니다. 나중에 바꾸는 방법은 [troubleshooting](troubleshooting.md#password-authentication-failed)을 참고하세요.

### `JWT_SECRET`

`.env.example`의 기본값은 안내 문구입니다. 그대로 두면 애플리케이션이 기동되지 않습니다(`DecodingException: Illegal base64 character`). **Base64로 인코딩된 32바이트 이상**의 값이 필요합니다.

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

macOS/Linux에서는 아래 명령으로 만들 수 있습니다.

```bash
openssl rand -base64 32
```

출력된 44자 문자열을 `.env`의 `JWT_SECRET`에 붙여 넣습니다. 이 값은 팀원과 공유하지 않고 각자 로컬에서만 사용합니다.

### 기능별로 필요한 값 (선택)

| 변수 | 비워 두면 | 채우면 |
|---|---|---|
| `KAKAO_REST_API_KEY` | 기동은 되지만 위치 검색·길찾기 API 호출이 실패합니다 (위치 검색은 `500 G-005`) | 카카오 개발자 사이트의 REST API 키 |
| `OLLAMA_CHAT_MODEL` | 기동은 되지만 Ollama로 채팅 답변을 만들 때 `LLM-002`로 실패합니다 | 사용할 채팅 모델 태그. 값이 있으면 3단계에서 `ollama-init`이 임베딩 모델과 함께 내려받습니다 |

`KAKAO_REST_API_KEY` 줄은 지우지 마세요. 키 자체가 없으면 기동 시점에 `Could not resolve placeholder` 오류가 납니다.

`.env`는 Git에 올라가지 않습니다(`.gitignore` 등록됨). 비밀 값은 이 파일에만 적습니다.

### 실행 환경 선택

`.env`의 `AI_MODE`가 답변 생성과 임베딩에 쓸 모델 서버를 정합니다. 기본값은 `ollama`라 그대로 두면 됩니다.

```dotenv
AI_MODE=ollama
```

| 값 | 뜻 |
|---|---|
| `ollama` (기본) | 답변 생성과 임베딩 모두 Ollama |
| `vllm` | 답변 생성과 임베딩 모두 vLLM. NVIDIA GPU 필요 ([vLLM으로 바꿔 실행하기](#vllm으로-바꿔-실행하기)) |
| `custom` | 둘을 다르게 쓸 때. `AI_CHAT_ENGINE`, `AI_EMBEDDING_ENGINE`에 각각 `ollama` 또는 `vllm`을 적습니다 |

- 백엔드와 `tools/ubot.ps1`이 같은 `AI_MODE`를 읽습니다. 백엔드는 기동할 때 한 번 읽으므로, 값을 바꾸면 백엔드를 다시 시작해야 합니다.
- `AI_CHAT_ENGINE`, `AI_EMBEDDING_ENGINE`은 `custom`일 때만 씁니다. 다른 값인데 이 둘이 채워져 있으면 백엔드가 기동하지 않습니다.
- **이전에 만든 `.env`를 쓰고 있다면 `AI_MODE` 줄을 추가하고, `COMPOSE_FILE`, `COMPOSE_PATH_SEPARATOR`, `COMPOSE_PROJECT_NAME`, `LLM_PROVIDER`, `EMBEDDING_PROVIDER` 줄은 지우세요.** `AI_MODE`가 없으면 `tools/ubot.ps1`이 실행되지 않습니다. `COMPOSE_PROJECT_NAME`이 남아 있으면 다른 이름의 Compose 프로젝트가 만들어져 기존 DB 볼륨을 쓰지 못합니다.

## 3. PostgreSQL + pgvector + PostGIS와 Ollama 실행

```powershell
.\tools\ubot.ps1 up
.\tools\ubot.ps1 status
```

`tools/ubot.ps1`은 `.env`의 `AI_MODE`에 맞는 Compose 파일을 골라 띄웁니다. `ollama`면 공통 서비스(PostgreSQL, Redis, Prometheus)와 Ollama를 띄우고, 떠 있던 vLLM 컨테이너는 내립니다. `postgres`와 `ollama` 서비스의 STATUS가 `(healthy)`가 될 때까지 기다립니다.

- PowerShell이 스크립트 실행을 막으면 `powershell -ExecutionPolicy Bypass -File .\tools\ubot.ps1 up`으로 실행합니다.
- 스크립트 없이 실행하려면 같은 일을 하는 명령을 직접 씁니다. macOS/Linux에서도 이 방법을 씁니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.ollama.yml up -d
```

- `-f` 없이 `docker compose up -d`만 실행하면 공통 서비스만 뜨고 Ollama는 뜨지 않습니다. 모델 서버는 별도 파일(`docker-compose.ollama.yml`, `docker-compose.vllm.yml`)에 있기 때문입니다.

DB 이미지에는 pgvector와 PostGIS 실행 파일이 설치되어 있습니다. extension 활성화와 테이블 생성은 5단계에서 애플리케이션을 시작할 때 Flyway가 담당합니다. 문제가 생기면 [Flyway 확인 절차](troubleshooting.md#vector-또는-postgis-extension이-없음)를 따르세요.

## 4. Embedding 모델 준비 확인

`ollama-init` 서비스가 `.env`의 `OLLAMA_EMBEDDING_MODEL`(기본 `bge-m3:567m`)을 같은 Compose의 Ollama에 다운로드합니다. 첫 다운로드에는 시간이 걸릴 수 있습니다.

Ollama 서비스를 다루는 명령에는 두 Compose 파일을 함께 지정합니다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.ollama.yml logs --tail=30 ollama-init
docker compose -f docker-compose.yml -f docker-compose.ollama.yml ps -a ollama-init
docker compose -f docker-compose.yml -f docker-compose.ollama.yml exec ollama ollama list
```

`ollama-init`의 종료 코드가 0이고 모델 목록에 `bge-m3:567m`이 보이면 다음 단계로 진행합니다.

`.env`의 `OLLAMA_CHAT_MODEL`에 값이 있으면 `ollama-init`이 채팅 모델도 함께 받습니다. 이미 받아 둔 모델은 다시 받지 않습니다. 나중에 값을 채웠다면 `ollama-init`을 다시 실행하거나 직접 받습니다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.ollama.yml run --rm ollama-init
docker compose -f docker-compose.yml -f docker-compose.ollama.yml exec ollama ollama pull <채팅모델이름>
```

호스트에서 `ollama pull`을 실행하면 별도의 호스트 Ollama에 다운로드될 수 있으므로 여기서는 사용하지 않습니다. 다운로드 실패 시 [모델 준비 문제](troubleshooting.md#ollama-모델이-준비되지-않음)를 참고하세요.

## 5. Spring Boot 실행

```powershell
.\gradlew.bat bootRun
```

Eclipse에서 `UbotBeApplication`을 실행해도 됩니다. 이 경우 Run Configuration의 작업 디렉터리가 프로젝트 루트여야 `.env`를 읽을 수 있습니다(기본값이 프로젝트 루트입니다).

별도 설정이 없으면 `local` 프로필로 실행됩니다. 기동 중 Flyway가 `V1`~`V15` 마이그레이션을 순서대로 적용해 extension과 테이블을 준비합니다. 적용 이력은 `flyway_schema_history` 테이블에 남습니다.

## 6. 동작 확인

새 터미널에서:

```powershell
curl.exe http://localhost:8080/actuator/health
```

기대 결과(일부):

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", ... },
    ...
  }
}
```

- `status`가 `UP` → 애플리케이션 기동 성공
- `components.db.status`가 `UP` → DB 연결 성공

둘 다 `UP`이면 애플리케이션과 DB의 기본 기동 확인이 끝났습니다. Health 응답만으로 실제 임베딩 생성·벡터 검색까지 검증된 것은 아닙니다.

## 7. API 호출해 보기

Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 전체 API를 보고 직접 호출할 수 있습니다. 오른쪽 위 `Authorize`에 access token을 넣으면 인증이 필요한 API도 호출됩니다.

인증 없이 호출할 수 있는 경로([architecture.md#인증](architecture.md#인증))를 제외한 요청은 회원가입 후 로그인해서 받은 access token을 `Authorization: Bearer <token>` 헤더에 넣어 호출하세요. 엔드포인트 구성은 [architecture.md](architecture.md#api-개요)를 참고하세요.

## 8. 챗봇까지 확인하려면 (선택)

`POST /chat/questions`로 실제 답변을 받으려면 채팅 모델, FAQ 데이터, 관리자 계정이 필요합니다. 처음에는 FAQ가 없어 질문하면 `CHAT-012`(검색 결과 없음)가, 채팅 모델이 비어 있으면 `LLM-002`가 반환됩니다.

- 기본 FAQ 1,000건과 기본 계정을 한 번에 넣으려면 `.\tools\reset-local-db.ps1`을 실행하고, 애플리케이션을 다시 시작한 뒤 임베딩 백필(`.\tools\backfill-embeddings.ps1`)을 실행합니다. **이 스크립트는 로컬 PostgreSQL 데이터를 모두 지우고 새로 만듭니다.**
- 지금 DB를 유지하려면 계정을 직접 만들고 관리자 API로 FAQ를 등록합니다.

두 방법 모두 [local-data.md](how-to/local-data.md)에 있습니다.

## 테스트만 실행하려면

JDK 17 이상과 실행 중인 Docker만 준비한 뒤 실행합니다.

```powershell
.\gradlew.bat test
```

Testcontainers가 Compose와 같은 Dockerfile로 테스트 전용 DB를 만들고 Flyway와 두 extension을 검증한 뒤 종료 시 정리합니다. 위의 `.env` 작성·Compose 기동 단계는 테스트에 필요하지 않습니다.

PR을 올리기 전에 CI와 같은 명령(`gradlew clean build --no-daemon`)으로 확인하려면 `.\tools\ci.ps1`을 실행합니다.

> 단, 실제 Ollama가 필요한 분석 테스트 하나가 로컬에서 함께 실행됩니다. Ollama 없이 돌리는 방법은 [troubleshooting](troubleshooting.md#로컬-테스트에서-intentclassificationanalysis가-실패함)을 참고하세요.

## 실패했다면

증상별 해결 방법은 [troubleshooting.md](troubleshooting.md)에 있습니다.

## 다음 단계

- 코드 구조와 흐름 → [architecture.md](architecture.md)
- DB를 직접 조회하려면 → [how-to/db-access.md](how-to/db-access.md)
- 채팅 테스트용 FAQ를 넣으려면 → [how-to/local-data.md](how-to/local-data.md)
- 로컬 DB를 기본 데이터로 초기화하려면 → [how-to/reset-local-db.md](how-to/reset-local-db.md)
- 프론트와 연동하려면 → [how-to/frontend-integration.md](how-to/frontend-integration.md)
- LLM·프롬프트 연결 방식 → [how-to/llm-module.md](how-to/llm-module.md)
- 설정값의 의미 → [reference/configuration.md](reference/configuration.md)
- 코드를 수정하고 PR을 올리려면 → [../CONTRIBUTING.md](../CONTRIBUTING.md)

## vLLM으로 바꿔 실행하기

NVIDIA GPU가 있는 PC에서는 Ollama 대신 vLLM으로 답변 생성과 임베딩을 모두 실행할 수 있습니다. DB는 그대로 쓰고 `AI_MODE`만 바꿉니다.

준비물은 Docker 컨테이너에서 GPU가 보이는 환경([확인 방법](../infra/llm/README.md#2-nvidia-gpu-확인))과 Docker Compose 2.24.4 이상입니다(`docker compose version`).

1. `.env`에서 `AI_MODE`를 바꿉니다.

    ```dotenv
    AI_MODE=vllm
    ```

2. 같은 명령으로 띄웁니다. Ollama 컨테이너를 내리고 vLLM 답변 생성 서버(`:8000`)와 임베딩 서버(`:8001`)를 띄운 뒤, 두 서버가 준비될 때까지 기다립니다.

    ```powershell
    .\tools\ubot.ps1 up
    ```

3. 백엔드를 다시 시작합니다(`.\gradlew.bat bootRun`).

4. 관리자 계정으로 임베딩 백필을 실행합니다. 지금 DB의 벡터는 Ollama로 만든 것이라, vLLM용 벡터를 한 번 채워야 합니다. 자세한 내용은 [백필](how-to/llm-module.md#백필)에 있습니다.

    ```powershell
    .\tools\backfill-embeddings.ps1 -Email <관리자 이메일>
    ```

알아 둘 점:

- **벡터는 임베딩 서버별로 따로 저장됩니다.** 같은 DB에 Ollama 벡터와 vLLM 벡터가 나란히 들어가고, 검색은 지금 서버의 벡터만 씁니다. 그래서 서버를 바꿔도 기존 벡터는 지워지지 않고, 되돌리면 그대로 다시 씁니다.
- **백필 전에는 검색 결과가 없습니다.** 새 서버의 벡터가 없는 동안에는 채팅이 `CHAT-012`로 끝나고, FAQ 수정은 `FAQ-002`로 실패하며, 미응답 질문은 기존 묶음을 찾지 못해 새 묶음을 만듭니다. FAQ 1,024건 기준으로 백필은 Ollama 약 80초, vLLM(RTX 3060) 약 24초가 걸렸습니다.
- **처음 실행할 때 모델을 내려받습니다.** 스크립트는 서버마다 300초까지 기다리고, 그 안에 준비되지 않으면 실패로 끝납니다. 컨테이너는 계속 내려받고 있으므로 조금 뒤에 `.\tools\ubot.ps1 up`을 다시 실행합니다. 받은 모델은 `llm_hf-cache` 볼륨에 남습니다.
- **실제 서버로 연결 확인**을 하려면 두 서버가 뜬 상태에서 `.\infra\llm\run-live-tests.ps1`을 실행합니다.
- **모델 서버 설정**은 `.env`의 vLLM 묶음과 [infra/llm/README.md](../infra/llm/README.md)를 참고하세요. GPU 메모리가 부족하면 `VLLM_GPU_MEMORY_UTILIZATION`, `VLLM_EMBEDDING_GPU_MEMORY_UTILIZATION`을 조정합니다.
- Ollama로 돌아갈 때도 `AI_MODE=ollama`로 바꾸고 `.\tools\ubot.ps1 up`과 백엔드 재시작을 하면 됩니다. 그 사이에 FAQ를 추가하거나 고쳤다면 백필을 한 번 더 실행합니다.

## 정리하기

```powershell
.\tools\ubot.ps1 down      # 컨테이너 제거, DB·모델 볼륨 유지
```

> `docker compose ... down -v`처럼 `-v`를 붙이면 함께 지정한 Compose 파일의 볼륨을 삭제합니다. 공통 파일은 PostgreSQL 데이터를, Ollama 파일은 받아 둔 Ollama 모델을, vLLM 파일은 받아 둔 모델(`llm_hf-cache`)을 지웁니다. 테스트 DB 정리에 필요한 명령이 아닙니다. 개발 데이터를 초기화하려는 경우에만 백업과 삭제 범위를 확인한 뒤 사용하세요.
