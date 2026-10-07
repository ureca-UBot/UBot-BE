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
| `OLLAMA_CHAT_MODEL` | 기동은 되지만 채팅 답변 생성이 `LLM-002`로 실패합니다 | 사용할 채팅 모델 태그. 값이 있으면 3단계에서 `ollama-init`이 임베딩 모델과 함께 내려받습니다 |

`KAKAO_REST_API_KEY` 줄은 지우지 마세요. 키 자체가 없으면 기동 시점에 `Could not resolve placeholder` 오류가 납니다.

`.env`는 Git에 올라가지 않습니다(`.gitignore` 등록됨). 비밀 값은 이 파일에만 적습니다.

### 실행 환경 선택

`.env` 맨 위의 "실행 환경 선택" 묶음이 어떤 모델 서버를 띄울지 정합니다. 기본값은 Ollama 환경이라 그대로 두면 됩니다.

```dotenv
COMPOSE_PATH_SEPARATOR=:
COMPOSE_FILE=docker-compose.yml:docker-compose.ollama.yml
LLM_PROVIDER=ollama
EMBEDDING_PROVIDER=ollama
```

- `COMPOSE_FILE`은 `docker compose` 명령이 읽을 파일 목록입니다. 공통 서비스(`docker-compose.yml`)에 환경별 모델 서버 파일을 더합니다.
- **이전에 만든 `.env`를 쓰고 있다면 `COMPOSE_PATH_SEPARATOR`와 `COMPOSE_FILE` 두 줄을 추가하세요.** `COMPOSE_FILE`이 없으면 `docker compose up -d`가 PostgreSQL·Redis·Prometheus만 띄우고 Ollama는 띄우지 않습니다. 두 줄을 추가해도 볼륨은 그대로라서 DB 데이터와 받아 둔 모델은 유지됩니다.
- GPU가 있는 PC에서 vLLM으로 실행하려면 [vLLM 환경으로 실행하기](#vllm-환경으로-실행하기)를 참고하세요.

## 3. PostgreSQL + pgvector + PostGIS와 Ollama 실행

```powershell
docker compose up -d --build
docker compose ps -a
```

`postgres`와 `ollama` 서비스의 STATUS가 `(healthy)`가 될 때까지 기다립니다. 실제 컨테이너 이름은 Compose 프로젝트명에 따라 달라집니다.

DB 이미지에는 pgvector와 PostGIS 실행 파일이 설치되어 있습니다. extension 활성화와 테이블 생성은 5단계에서 애플리케이션을 시작할 때 Flyway가 담당합니다. 문제가 생기면 [Flyway 확인 절차](troubleshooting.md#vector-또는-postgis-extension이-없음)를 따르세요.

## 4. Embedding 모델 준비 확인

`ollama-init` 서비스가 `.env`의 `OLLAMA_EMBEDDING_MODEL`(기본 `bge-m3:567m`)을 같은 Compose의 Ollama에 다운로드합니다. 첫 다운로드에는 시간이 걸릴 수 있습니다.

```powershell
docker compose logs --tail=30 ollama-init
docker compose ps -a ollama-init
docker compose exec ollama ollama list
```

`ollama-init`의 종료 코드가 0이고 모델 목록에 `bge-m3:567m`이 보이면 다음 단계로 진행합니다.

`.env`의 `OLLAMA_CHAT_MODEL`에 값이 있으면 `ollama-init`이 채팅 모델도 함께 받습니다. 이미 받아 둔 채팅 모델은 다시 받지 않습니다. 나중에 값을 채웠다면 `ollama-init`을 다시 실행하거나 직접 받습니다.

```powershell
docker compose run --rm ollama-init
docker compose exec ollama ollama pull <채팅모델이름>
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

`POST /chat/questions`로 실제 답변을 받으려면 채팅 모델, FAQ 데이터, FAQ를 등록할 관리자 계정이 필요합니다. 처음에는 FAQ가 없어 질문하면 `CHAT-012`(검색 결과 없음)가, 채팅 모델이 비어 있으면 `LLM-002`가 반환됩니다. 준비 방법은 [local-data.md](how-to/local-data.md)를 따르세요.

## 테스트만 실행하려면

JDK 17 이상과 실행 중인 Docker만 준비한 뒤 실행합니다.

```powershell
.\gradlew.bat test
```

Testcontainers가 Compose와 같은 Dockerfile로 테스트 전용 DB를 만들고 Flyway와 두 extension을 검증한 뒤 종료 시 정리합니다. 위의 `.env` 작성·Compose 기동 단계는 테스트에 필요하지 않습니다.

> 단, 실제 Ollama가 필요한 분석 테스트 하나가 로컬에서 함께 실행됩니다. Ollama 없이 돌리는 방법은 [troubleshooting](troubleshooting.md#로컬-테스트에서-intentclassificationanalysis가-실패함)을 참고하세요.

## 실패했다면

증상별 해결 방법은 [troubleshooting.md](troubleshooting.md)에 있습니다.

## 다음 단계

- 코드 구조와 흐름 → [architecture.md](architecture.md)
- DB를 직접 조회하려면 → [how-to/db-access.md](how-to/db-access.md)
- 채팅 테스트용 FAQ를 넣으려면 → [how-to/local-data.md](how-to/local-data.md)
- 프론트와 연동하려면 → [how-to/frontend-integration.md](how-to/frontend-integration.md)
- LLM·프롬프트 연결 방식 → [how-to/llm-module.md](how-to/llm-module.md)
- 설정값의 의미 → [reference/configuration.md](reference/configuration.md)
- 코드를 수정하고 PR을 올리려면 → [../CONTRIBUTING.md](../CONTRIBUTING.md)

## vLLM 환경으로 실행하기

NVIDIA GPU가 있는 PC에서는 Ollama 대신 vLLM으로 답변 생성과 임베딩을 모두 실행할 수 있습니다. 명령은 같고 `.env`의 "실행 환경 선택" 묶음만 바꿉니다.

준비물은 Docker 컨테이너에서 GPU가 보이는 환경([확인 방법](../infra/llm/README.md#2-nvidia-gpu-확인))과 Docker Compose 2.24.4 이상입니다(`docker compose version`).

1. 지금 환경을 내립니다. 프로젝트 이름이 바뀌므로, 값을 바꾸기 **전에** 내려야 합니다.

    ```powershell
    docker compose down
    ```

2. `.env`에서 Ollama 환경 세 줄을 주석으로 바꾸고 vLLM 환경 네 줄의 주석을 풉니다.

    ```dotenv
    COMPOSE_FILE=docker-compose.yml:docker-compose.vllm.yml
    COMPOSE_PROJECT_NAME=ubot-be-vllm
    LLM_PROVIDER=openai-compatible
    EMBEDDING_PROVIDER=openai-compatible
    ```

3. 같은 명령으로 띄웁니다. PostgreSQL·Redis·Prometheus와 vLLM 답변 생성 서버(`:8000`), 임베딩 서버(`:8001`)가 뜹니다.

    ```powershell
    docker compose up -d --build
    ```

4. 두 모델 서버가 준비될 때까지 기다린 뒤 백엔드 연결을 확인합니다. 아래 스크립트가 준비를 기다렸다가 실제 서버로 Live 테스트를 실행합니다.

    ```powershell
    .\infra\llm\run-live-tests.ps1
    ```

5. 백엔드를 실행합니다(`.\gradlew.bat bootRun`).

알아 둘 점:

- **DB가 따로입니다.** `COMPOSE_PROJECT_NAME`이 달라 PostgreSQL 볼륨을 따로 씁니다. Ollama로 만든 벡터와 vLLM으로 만든 벡터가 섞이지 않게 하기 위해서입니다. 그래서 vLLM 환경에서는 계정과 FAQ를 다시 준비해야 합니다([how-to/local-data.md](how-to/local-data.md)).
- **처음 실행할 때 모델을 내려받습니다.** 답변 생성 모델과 임베딩 모델을 받는 동안에는 서버가 준비되지 않습니다. 스크립트는 기본 300초까지 기다리므로, 처음에는 `.\infra\llm\run-live-tests.ps1 -WaitSeconds 1800`처럼 늘려서 실행합니다. 받은 모델은 `llm_hf-cache` 볼륨에 남습니다. `infra/llm`으로 이미 받아 둔 PC에서는 "volume already exists" 경고가 나오지만 그대로 재사용합니다.
- **모델 서버 설정**은 `.env`의 "vLLM 환경" 묶음과 [infra/llm/README.md](../infra/llm/README.md)를 참고하세요. GPU 메모리가 부족하면 `VLLM_GPU_MEMORY_UTILIZATION`, `VLLM_EMBEDDING_GPU_MEMORY_UTILIZATION`을 조정합니다.
- Ollama 환경으로 돌아갈 때도 먼저 `docker compose down`을 한 뒤 `.env`를 되돌립니다.

## 정리하기

```powershell
docker compose down      # 개발 컨테이너 제거, DB·모델 볼륨 유지
```

> `docker compose down -v`는 PostgreSQL 데이터와 Ollama 다운로드 모델이 담긴 볼륨을 모두 삭제합니다. vLLM 환경에서는 받아 둔 모델(`llm_hf-cache`)도 함께 삭제합니다. 테스트 DB 정리에 필요한 명령이 아닙니다. 개발 데이터를 초기화하려는 경우에만 백업과 삭제 범위를 확인한 뒤 사용하세요.
