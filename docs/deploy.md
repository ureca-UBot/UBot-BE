# 배포

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30
>
> GitHub PR·이슈 상태: 2026-09-30 09:23 KST 조회 기준

배포는 **수동 실행 CD 워크플로우**(`Backend Manual CD`)로 EC2 스테이징 서버에 올립니다. 같은 구성 파일로 로컬에서 배포 형태를 재현할 수도 있습니다.

## 배포 범위

- `push`·`pull_request` 자동 배포 트리거는 두지 않고 `workflow_dispatch` 수동 실행만 씁니다.
- CD는 백엔드, Nginx, 공통 서비스(PostgreSQL, Redis, Prometheus)를 올립니다. 서버 `.env`의 `AI_RUNTIME_MANAGED`가 `true`면 모델 서버(Ollama 또는 vLLM)도 같은 서버에 함께 띄웁니다.
- 무엇을 띄울지는 워크플로 입력이 아니라 서버의 `~/ubot/.env`가 정합니다. `AI_MODE`가 모델 서버 종류를, `AI_RUNTIME_MANAGED`가 모델 서버를 이 서버에 띄울지를 정합니다.
- `develop`, `main` 브랜치에서만 실행됩니다. 다른 브랜치로 실행하면 job이 건너뛰어집니다.
- 도메인·HTTPS 적용, 서버 자동 시작·중지는 후속 작업입니다(#60, #88). WebSocket용 Nginx 설정은 WebSocket endpoint가 정해진 뒤 적용합니다(#77).

## 구성 파일

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 애플리케이션 이미지. `eclipse-temurin:21-jdk`에서 `bootJar`로 빌드하고 `21-jre`로 실행 |
| `.dockerignore` | `.env`, `.git`, 빌드 산출물, IDE 설정, 키 파일을 이미지 빌드 컨텍스트에서 제외 |
| `docker-compose.yml` | 공통 서비스 (`postgres`, `redis`, `prometheus`) |
| `docker-compose.deploy.yml` | `backend`, `nginx` 서비스 정의. 공통 파일과 함께 사용 |
| `docker-compose.ollama.yml` | Ollama 모델 서버 (`ollama`, `ollama-init`). `AI_RUNTIME_MANAGED=true`이고 `AI_MODE=ollama`일 때 함께 사용 |
| `docker-compose.vllm.yml`, `infra/llm/docker-compose.yml` | vLLM 모델 서버 (`vllm`, `vllm-embedding`). `AI_RUNTIME_MANAGED=true`이고 `AI_MODE=vllm`일 때 함께 사용 |
| `infra/nginx/nginx.conf` | 80 포트의 모든 요청을 `backend:8080`으로 프록시. `/api` 접두사 제거와 게스트 채팅 속도 제한 포함 |
| `src/main/resources/application-prod.yml` | 운영 프로필 설정. health 상세 숨김, Swagger 끔, graceful shutdown ([주의할 점](#주의할-점) 참고) |
| `.github/workflows/cd-manual.yml` | 이미지 빌드 → EC2 전송 → 서버 `.env` 검사 → Compose 기동 → health check |

### 서비스 구성

```text
인터넷 ──:80──▶ nginx ──▶ backend:8080 ──▶ postgres:5432, redis:6379
                                      └──▶ 모델 서버 (AI_MODE에 따라 Ollama 또는 vLLM)
```

- `backend`는 호스트 포트를 열지 않고(`expose: 8080`) Compose 네트워크 안에서만 접근됩니다. 외부 요청은 `nginx`를 거칩니다.
- `backend`는 `.env`를 `env_file`로 읽고, 아래 값은 Compose가 덮어씁니다: `SPRING_PROFILES_ACTIVE=local`, `POSTGRES_HOST=postgres`, `POSTGRES_PORT=5432`, `OLLAMA_BASE_URL=http://ollama:11434`, `REDIS_HOST=redis`, `REDIS_PORT=6379`.
- `backend`는 `postgres`, `redis`가 healthy가 된 뒤 시작합니다. 기동 시 Flyway가 마이그레이션을 적용합니다. 모델 서버는 기다리지 않으며, CD가 모델 서버를 먼저 띄우고 준비를 확인한 뒤 `backend`를 올립니다.
- 이미지 이름은 `BACKEND_IMAGE` 환경변수로 지정합니다. 기본값은 `ubot-be:local`이고, CD는 `ubot-be:<커밋 SHA>`로 덮어씁니다.
- `nginx`는 `proxy_read_timeout 180s`로 채팅 응답 대기 시간(`CHAT_RESPONSE_TIMEOUT_MILLIS` 기본 180초)과 같은 값을 씁니다. `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto` 헤더를 넘깁니다.
- `nginx`는 `/api/`로 시작하는 요청에서 `/api`를 떼고 `backend`로 넘깁니다(`/api/chat/questions` → `/chat/questions`). UBot-FE의 공용 API 클라이언트가 `/api` 접두사를 붙여 호출하기 때문입니다. 접두사 없는 경로도 그대로 프록시합니다.
- `nginx`는 `/chat/`와 `/api/chat/` 경로의 게스트 요청(`Authorization: Bearer …` 형식의 헤더가 없는 요청)에만 IP별 요청 속도 제한을 겁니다. 회원 요청은 제한하지 않습니다. `.env`의 `CHAT_GUEST_RATE_LIMIT_RATE`(기본 `30r/m`)와 `CHAT_GUEST_RATE_LIMIT_BURST`(기본 `10`)로 조절하며, 넘으면 백엔드로 넘기지 않고 `429 RATE-001`을 반환합니다. `nginx.conf`는 컨테이너 시작 시 이 환경변수를 채워 넣는 템플릿으로 마운트되므로, 값을 바꾼 뒤에는 `nginx`를 다시 만들어야 합니다.

## 서버의 `.env`

서버의 `~/ubot/.env`가 배포 구성을 정합니다. `.env.example`을 바탕으로 만들되 개발용 `.env`를 그대로 복사하지 말고, 비밀 값과 아래 값을 서버에 맞게 넣습니다. CD는 배포 전에 필요한 키가 있는지 검사하고, 없으면 이미지를 올리기 전에 실패합니다.

| 조건 | 있어야 하는 키 |
|---|---|
| 항상 | `SPRING_PROFILES_ACTIVE`(`prod`여야 함), `AI_MODE`, `AI_RUNTIME_MANAGED`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`, `KAKAO_REST_API_KEY` |
| `AI_MODE=ollama` | `OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_MODEL` |
| `AI_MODE=vllm` | `LLM_BASE_URL`, `LLM_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_MODEL` |
| `AI_MODE=custom` | `AI_CHAT_ENGINE`, `AI_EMBEDDING_ENGINE` |
| `AI_RUNTIME_MANAGED=true`, `AI_MODE=ollama` | 위에 더해 `OLLAMA_PORT` |
| `AI_RUNTIME_MANAGED=true`, `AI_MODE=vllm` | 위에 더해 `LLM_HF_MODEL`, `LLM_SERVED_MODEL_NAME`, `EMBEDDING_HF_MODEL`, `EMBEDDING_SERVED_MODEL_NAME`. 서버에서 `nvidia-smi`가 동작해야 함 |

- `AI_RUNTIME_MANAGED`는 `true` 또는 `false`입니다. `true`면 CD가 모델 서버를 이 서버에 띄우고, `false`면 띄우지 않고 `.env`의 주소로 다른 곳의 모델 서버를 호출합니다.
- `AI_MODE=custom`은 `AI_RUNTIME_MANAGED=true`와 함께 쓸 수 없습니다.
- CD는 키가 있는지만 확인하고 주소가 맞는지는 확인하지 않습니다. 백엔드는 컨테이너 안에서 실행되므로 `localhost`는 백엔드 자신을 가리킵니다. 모델 서버를 이 서버에 함께 띄울 때는 Compose 서비스 이름으로 적습니다.

```dotenv
# vLLM을 이 서버에 함께 띄우는 경우
SPRING_PROFILES_ACTIVE=prod
AI_MODE=vllm
AI_RUNTIME_MANAGED=true
LLM_BASE_URL=http://vllm:8000/v1
EMBEDDING_BASE_URL=http://vllm-embedding:8000/v1
```

```dotenv
# 다른 GPU 서버의 vLLM을 호출하는 경우
SPRING_PROFILES_ACTIVE=prod
AI_MODE=vllm
AI_RUNTIME_MANAGED=false
LLM_BASE_URL=http://<GPU 서버 주소>:8000/v1
EMBEDDING_BASE_URL=http://<GPU 서버 주소>:8001/v1
```

## 로컬에서 배포 형태 실행하기

로컬에는 GPU가 없는 경우가 많아 Ollama 구성으로 재현합니다. `.env`의 `AI_MODE`가 `ollama`인 상태에서 실행합니다.

```powershell
docker build -t ubot-be:local .
docker compose -f docker-compose.yml -f docker-compose.ollama.yml -f docker-compose.deploy.yml up -d
```

빌드는 컨테이너 안에서 Gradle을 실행하므로 로컬에 Java가 없어도 됩니다. 대신 매번 의존성을 새로 받습니다. 호스트의 80 포트가 비어 있어야 합니다.

확인 (Nginx를 거치므로 8080이 아니라 80 포트):

```powershell
docker compose -f docker-compose.yml -f docker-compose.ollama.yml -f docker-compose.deploy.yml ps
curl.exe http://localhost/actuator/health
```

## 수동 CD (`Backend Manual CD`)

### 실행 방법

GitHub → Actions → `Backend Manual CD` → `Run workflow`에서 브랜치를 골라 실행합니다. 입력값은 없습니다.

- GitHub Environment `staging`을 사용합니다. Environment에 보호 규칙이 있으면 승인 후 진행됩니다.
- 동시에 하나만 실행되며(`concurrency: ubot-backend-deploy`), 뒤에 실행한 것은 앞 배포가 끝날 때까지 기다립니다. 제한 시간은 60분입니다.

### 필요한 Secrets

| Secret | 값 |
|---|---|
| `EC2_HOST` | 배포 서버 호스트 |
| `EC2_USER` | SSH 사용자 |
| `EC2_SSH_KEY` | SSH 개인 키 (PEM 전체) |

### 워크플로우 단계

1. `docker build`로 `ubot-be:<커밋 SHA>` 이미지를 만들고 `docker save`로 압축합니다.
2. Compose 파일 4개(`docker-compose.yml`, `docker-compose.deploy.yml`, `docker-compose.ollama.yml`, `docker-compose.vllm.yml`)와 `infra/postgres/Dockerfile`, `infra/nginx/nginx.conf`, `infra/prometheus/prometheus.yml`, `infra/llm/docker-compose.yml`을 묶습니다.
3. SSH 연결을 설정하고 확인합니다.
4. 서버 환경을 검사합니다. Docker·Compose 버전을 출력하고, `~/ubot/.env`가 있는지, [필요한 키](#서버의-env)가 다 있는지, `SPRING_PROFILES_ACTIVE`가 `prod`인지 확인합니다. vLLM을 이 서버에 띄우는 구성이면 `nvidia-smi`도 실행합니다. 하나라도 맞지 않으면 여기서 실패합니다.
5. 두 압축 파일을 `~/ubot-deploy/`로 복사합니다.
6. 서버에서 구성 파일을 `~/ubot`에 풀고 이미지를 `docker load`한 뒤, Compose 프로젝트 `ubot`으로 아래 순서를 실행합니다. 공통 파일과 배포 파일은 항상 쓰고, `AI_RUNTIME_MANAGED=true`면 `AI_MODE`에 맞는 모델 서버 파일을 더합니다.
   - `config`: 고른 파일 조합이 유효한지 확인합니다.
   - 이전 모델 서버 컨테이너(`ollama`, `ollama-init`, `vllm`, `vllm-embedding`)를 내립니다. `AI_MODE`를 바꿔 배포해도 이전 모델 서버가 남지 않습니다. 볼륨은 지우지 않습니다.
   - `postgres`, `redis`, `prometheus`를 올립니다. `postgres` 이미지가 서버에 없으면 `infra/postgres/Dockerfile`로 빌드합니다.
   - `AI_RUNTIME_MANAGED=true`면 모델 서버를 올리고 준비될 때까지 기다립니다. Ollama는 `ollama`와 `ollama-init`을 올리고 `/api/tags`가 응답할 때까지 5초 간격으로 최대 60회 확인합니다. vLLM은 `vllm`과 `vllm-embedding`을 올리고 각각 `/v1/models`가 응답할 때까지 5초 간격으로 최대 120회 확인합니다. 시간 안에 준비되지 않으면 해당 컨테이너 로그 200줄을 출력하고 실패합니다.
   - `backend`를 새 이미지로 올립니다.
   - `nginx`를 강제로 다시 만듭니다(`--no-deps --force-recreate`).
   - `ps -a`로 컨테이너 상태를 출력합니다.
7. 서버에서 `http://127.0.0.1/actuator/health`를 5초 간격으로 최대 30회 확인합니다. 실패하면 컨테이너 상태와 `backend`·`nginx` 로그 200줄을 출력하고 실패로 끝납니다.

### 서버 사전 준비 (최초 1회)

- Docker와 Docker Compose 플러그인을 설치합니다.
- `~/ubot/.env`를 만듭니다([서버의 `.env`](#서버의-env)).
- 보안 그룹에서 80 포트를 엽니다.
- vLLM을 이 서버에 띄우려면 NVIDIA 드라이버와 NVIDIA Container Toolkit을 설치해 `nvidia-smi`와 컨테이너의 GPU 사용이 되게 합니다.
- 다른 서버의 모델 서버를 호출하려면(`AI_RUNTIME_MANAGED=false`) 그 서버의 포트가 백엔드 서버에서 접근 가능해야 합니다. vLLM은 답변 생성(8000)과 임베딩(8001) 두 포트가 필요합니다.

모델은 모델 서버가 처음 뜰 때 내려받습니다.

- **Ollama**: `ollama-init`이 `.env`의 `OLLAMA_EMBEDDING_MODEL`(없으면 `bge-m3:567m`)과 `OLLAMA_CHAT_MODEL` 중 서버에 없는 모델만 받습니다. CD는 `ollama`가 응답하는지만 기다리고 내려받기가 끝나는 것은 기다리지 않습니다. 첫 배포 직후에는 아래 명령으로 모델이 받아졌는지 확인합니다.
- **vLLM**: 컨테이너가 Hugging Face에서 모델을 받은 뒤에 응답을 시작합니다. 첫 배포에서는 내려받는 시간 때문에 준비 대기(서버마다 10분)를 넘겨 실패할 수 있습니다. 컨테이너는 계속 내려받고 있으므로 다시 실행하면 됩니다. 받은 모델은 `llm_hf-cache` 볼륨에 남습니다.

```bash
cd ~/ubot
docker compose -p ubot --env-file .env -f docker-compose.yml -f docker-compose.deploy.yml -f docker-compose.ollama.yml exec ollama ollama list
```

### 서버에서 상태 확인·롤백

Compose 명령은 항상 프로젝트 이름, `.env`, 배포에 쓴 파일을 함께 지정합니다. 아래는 공통 구성이고, 모델 서버를 이 서버에 띄웠다면 `-f docker-compose.ollama.yml` 또는 `-f docker-compose.vllm.yml`을 덧붙입니다.

```bash
cd ~/ubot
docker compose -p ubot --env-file .env -f docker-compose.yml -f docker-compose.deploy.yml ps -a
docker compose -p ubot --env-file .env -f docker-compose.yml -f docker-compose.deploy.yml logs --tail=200 backend
```

배포할 때마다 `ubot-be:<커밋 SHA>` 이미지가 서버에 남습니다. 이전 커밋으로 되돌리려면 그 태그로 `backend`를 다시 올립니다.

```bash
docker image ls ubot-be
BACKEND_IMAGE=ubot-be:<이전 커밋 SHA> docker compose -p ubot --env-file .env -f docker-compose.yml -f docker-compose.deploy.yml up -d backend
BACKEND_IMAGE=ubot-be:<이전 커밋 SHA> docker compose -p ubot --env-file .env -f docker-compose.yml -f docker-compose.deploy.yml up -d --no-deps --force-recreate nginx
```

워크플로우는 오래된 이미지와 `~/ubot-deploy/`의 압축 파일을 정리하지 않습니다. 디스크 여유를 주기적으로 확인하세요.

## 주의할 점

- **백엔드는 지금 `local` 프로필로 뜹니다.** CD는 `.env`의 `SPRING_PROFILES_ACTIVE`가 `prod`인지 검사하지만, `docker-compose.deploy.yml`이 이 값을 `local`로 덮어씁니다. 그래서 `application-prod.yml`은 적용되지 않고, `/actuator/health`가 DB 등 상세 정보를 노출하며(`show-details: always`) Swagger UI도 인증 없이 열려 있습니다. `prod`로 띄우려면 배포 파일의 덮어쓰기를 지워야 합니다.
- **`OLLAMA_BASE_URL`도 배포 파일이 `http://ollama:11434`로 덮어씁니다.** Ollama를 이 서버에 함께 띄울 때는 맞는 값이지만, 다른 서버의 Ollama를 호출하는 구성(`AI_MODE=ollama`, `AI_RUNTIME_MANAGED=false`)에서는 `.env`에 적은 주소가 무시됩니다.
- **임베딩 서버를 바꾼 뒤에는 백필을 실행합니다.** `AI_MODE`나 임베딩 모델을 바꿔 배포하면 새 Embedding Profile에 벡터가 없어서, 백필 전에는 채팅 검색 결과가 없습니다([백필](how-to/llm-module.md#백필)). 빈 DB에서 처음부터 한 서버만 쓰면 필요 없습니다.
- Nginx는 HTTP 80만 사용합니다. TLS(HTTPS) 설정은 없습니다(후속 작업).
- 백엔드만 새로 띄우고 Nginx를 그대로 두면 Nginx가 이전 백엔드 주소로 요청을 보내 502가 날 수 있습니다(#77 리뷰). 그래서 CD는 백엔드 교체 뒤 Nginx를 강제로 다시 만듭니다. 서버에서 손으로 백엔드를 교체했다면 Nginx도 다시 만드세요.

```bash
docker compose -p ubot --env-file .env -f docker-compose.yml -f docker-compose.deploy.yml up -d --no-deps --force-recreate nginx
```

- `CHAT_RESPONSE_TIMEOUT_MILLIS`를 180초보다 크게 바꾸면 `nginx.conf`의 `proxy_read_timeout`도 함께 늘려야 합니다.
- DB 마이그레이션은 `backend` 기동 시 자동 적용됩니다. 되돌릴 수 없는 마이그레이션이 포함된 배포는 이미지 롤백만으로 DB가 되돌아가지 않습니다.
- DB 데이터와 받아 둔 모델은 Compose 볼륨에 저장됩니다. `docker compose down -v`는 명령에 지정한 Compose 파일의 볼륨을 모두 삭제합니다.
