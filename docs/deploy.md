# 배포

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30
>
> GitHub PR·이슈 상태: 2026-09-30 09:23 KST 조회 기준

배포는 **수동 실행 CD 워크플로우**(`Backend Manual CD`)로 EC2 스테이징 서버에 올립니다. 같은 구성 파일로 로컬에서 배포 형태를 재현할 수도 있습니다.

## 배포 범위 (이슈 #60, PR #77·#88에서 정한 것)

- `push`·`pull_request` 자동 배포 트리거는 두지 않고 `workflow_dispatch` 수동 실행만 씁니다.
- CD 대상은 **Spring Boot 백엔드뿐**입니다. Ollama·LLM 모델 배포와 GPU 서버 구성은 범위 밖이며 별도 인프라 작업으로 관리합니다.
- LLM 서버의 운영 endpoint와 GPU 인프라가 확정되기 전까지 **실제 운영 배포는 보류**합니다. 지금 CD는 스테이징용입니다.
- LLM 서버를 백엔드와 분리할 수 있도록 연결 정보는 `OLLAMA_BASE_URL` 등 환경변수로 주입합니다. 현재 스테이징은 PostgreSQL·Ollama·백엔드·Nginx를 같은 Compose 프로젝트(`ubot`)로 띄웁니다(#88에서 확인).
- 도메인·HTTPS 적용, 서버 자동 시작·중지는 후속 작업입니다(#60, #88). WebSocket용 Nginx 설정은 WebSocket endpoint가 정해진 뒤 적용합니다(#77).

## 구성 파일

| 파일 | 역할 |
|---|---|
| `Dockerfile` | 애플리케이션 이미지. `eclipse-temurin:21-jdk`에서 `bootJar`로 빌드하고 `21-jre`로 실행 |
| `.dockerignore` | `.env`, `.git`, 빌드 산출물, IDE 설정, 키 파일을 이미지 빌드 컨텍스트에서 제외 |
| `docker-compose.deploy.yml` | `backend`, `nginx` 서비스 정의. 기본 `docker-compose.yml`의 `postgres`·`ollama`와 함께 사용 |
| `infra/nginx/nginx.conf` | 80 포트의 모든 요청을 `backend:8080`으로 프록시. `/api` 접두사 제거와 게스트 채팅 속도 제한 포함 |
| `.github/workflows/cd-manual.yml` | 이미지 빌드 → EC2 전송 → Compose 재기동 → health check |

### 서비스 구성

```text
인터넷 ──:80──▶ nginx ──▶ backend:8080 ──▶ postgres:5432
                                      └──▶ ollama:11434
```

- `backend`는 호스트 포트를 열지 않고(`expose: 8080`) Compose 네트워크 안에서만 접근됩니다. 외부 요청은 `nginx`를 거칩니다.
- `backend`는 `.env`를 `env_file`로 읽고, 컨테이너 네트워크에 맞는 값만 덮어씁니다: `SPRING_PROFILES_ACTIVE=local`, `POSTGRES_HOST=postgres`, `POSTGRES_PORT=5432`, `OLLAMA_BASE_URL=http://ollama:11434`.
- `backend`는 `postgres`, `ollama`가 healthy가 된 뒤 시작합니다. 기동 시 Flyway가 마이그레이션을 적용합니다.
- 이미지 이름은 `BACKEND_IMAGE` 환경변수로 지정합니다. 기본값은 `ubot-be:local`입니다.
- `nginx`는 `proxy_read_timeout 180s`로 채팅 응답 대기 시간(`CHAT_RESPONSE_TIMEOUT_MILLIS` 기본 180초)과 같은 값을 씁니다. `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto` 헤더를 넘깁니다.
- `nginx`는 `/api/`로 시작하는 요청에서 `/api`를 떼고 `backend`로 넘깁니다(`/api/chat/questions` → `/chat/questions`). UBot-FE의 공용 API 클라이언트가 `/api` 접두사를 붙여 호출하기 때문입니다. 접두사 없는 경로도 그대로 프록시합니다.
- `nginx`는 `/chat/`와 `/api/chat/` 경로의 게스트 요청(`Authorization` 헤더가 없는 요청)에만 IP별 요청 속도 제한을 겁니다. 회원 요청은 제한하지 않습니다. `.env`의 `CHAT_GUEST_RATE_LIMIT_RATE`(기본 `30r/m`)와 `CHAT_GUEST_RATE_LIMIT_BURST`(기본 `10`)로 조절하며, 넘으면 백엔드로 넘기지 않고 `429 RATE-001`을 반환합니다. `nginx.conf`는 컨테이너 시작 시 이 환경변수를 채워 넣는 템플릿으로 마운트되므로, 값을 바꾼 뒤에는 `nginx`를 다시 만들어야 합니다.

## 로컬에서 배포 형태 실행하기

```powershell
docker build -t ubot-be:local .
docker compose -f docker-compose.yml -f docker-compose.deploy.yml up -d
```

빌드는 컨테이너 안에서 Gradle을 실행하므로 로컬에 Java가 없어도 됩니다. 대신 매번 의존성을 새로 받습니다. 호스트의 80 포트가 비어 있어야 합니다.

확인 (Nginx를 거치므로 8080이 아니라 80 포트):

```powershell
docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps
curl.exe http://localhost/actuator/health
```

## 수동 CD (`Backend Manual CD`)

### 실행 방법

GitHub → Actions → `Backend Manual CD` → `Run workflow`에서 브랜치를 `develop`으로 선택해 실행합니다.

- `develop` 이외의 브랜치로 실행하면 job이 건너뛰어집니다.
- GitHub Environment `staging`을 사용합니다. Environment에 보호 규칙이 있으면 승인 후 진행됩니다.
- 동시에 하나만 실행되며(`concurrency: ubot-backend-deploy`), 뒤에 실행한 것은 앞 배포가 끝날 때까지 기다립니다. 제한 시간은 45분입니다.

### 필요한 Secrets

| Secret | 값 |
|---|---|
| `EC2_HOST` | 배포 서버 호스트 |
| `EC2_USER` | SSH 사용자 |
| `EC2_SSH_KEY` | SSH 개인 키 (PEM 전체) |

### 워크플로우 단계

1. `docker build`로 `ubot-be:<커밋 SHA>` 이미지를 만들고 `docker save`로 압축합니다.
2. `docker-compose.yml`, `docker-compose.deploy.yml`, `infra/postgres/Dockerfile`, `infra/nginx/nginx.conf`를 묶습니다.
3. SSH 연결을 설정하고 서버에서 Docker·Compose 버전과 `~/ubot/.env` 존재를 확인합니다. `.env`가 없으면 여기서 실패합니다.
4. 두 압축 파일을 `~/ubot-deploy/`로 복사합니다.
5. 서버에서 구성 파일을 `~/ubot`에 풀고, 이미지를 `docker load`한 뒤 Compose 프로젝트 `ubot`으로 실행합니다.
   - `up -d backend`: 새 이미지로 `backend`를 올립니다. 의존 서비스인 `postgres`, `ollama`가 없으면 함께 시작하며, `postgres` 이미지가 서버에 없으면 `infra/postgres/Dockerfile`로 빌드합니다.
   - `up -d --no-deps --force-recreate nginx`: 설정 파일 변경을 반영하도록 `nginx`를 다시 만듭니다.
6. 서버에서 `http://127.0.0.1/actuator/health`를 5초 간격으로 최대 30회 확인합니다. 실패하면 컨테이너 상태와 `backend`·`nginx` 로그 200줄을 출력하고 실패로 끝납니다.

### 서버 사전 준비 (최초 1회)

- Docker와 Docker Compose 플러그인을 설치합니다.
- `~/ubot/.env`를 만듭니다. 개발용 `.env`를 복사하지 말고 서버용 값(`POSTGRES_PASSWORD`, `JWT_SECRET`, `OLLAMA_CHAT_MODEL`, `KAKAO_REST_API_KEY` 등)을 따로 준비합니다. 필요한 키 목록은 [configuration.md](reference/configuration.md#개발-환경변수)를 참고하세요.
- 보안 그룹에서 80 포트를 엽니다.
- **Ollama 모델을 직접 받습니다.** CD는 `ollama-init`을 실행하지 않으므로 임베딩 모델과 채팅 모델 모두 서버에서 한 번 받아야 합니다. 모델은 Compose 볼륨에 남습니다.

```bash
cd ~/ubot
docker compose -p ubot exec ollama ollama pull bge-m3:567m
docker compose -p ubot exec ollama ollama pull <채팅모델이름>
docker compose -p ubot exec ollama ollama list
```

### 서버에서 상태 확인·롤백

Compose 명령은 항상 프로젝트 이름과 두 파일을 함께 지정합니다.

```bash
cd ~/ubot
docker compose -p ubot -f docker-compose.yml -f docker-compose.deploy.yml ps
docker compose -p ubot -f docker-compose.yml -f docker-compose.deploy.yml logs --tail=200 backend
```

배포할 때마다 `ubot-be:<커밋 SHA>` 이미지가 서버에 남습니다. 이전 커밋으로 되돌리려면 그 태그로 `backend`를 다시 올립니다.

```bash
docker image ls ubot-be
BACKEND_IMAGE=ubot-be:<이전 커밋 SHA> docker compose -p ubot -f docker-compose.yml -f docker-compose.deploy.yml up -d backend
BACKEND_IMAGE=ubot-be:<이전 커밋 SHA> docker compose -p ubot -f docker-compose.yml -f docker-compose.deploy.yml up -d --no-deps --force-recreate nginx
```

워크플로우는 오래된 이미지와 `~/ubot-deploy/`의 압축 파일을 정리하지 않습니다. 디스크 여유를 주기적으로 확인하세요.

## 주의할 점

- **`docker-compose.deploy.yml`은 `SPRING_PROFILES_ACTIVE: local`을 사용합니다.** 운영 환경을 위한 별도 프로필은 아직 없습니다. 그 결과 `/actuator/health`가 DB 등 상세 정보를 그대로 노출하고(`show-details: always`), Swagger UI도 인증 없이 열려 있습니다.
- Nginx는 HTTP 80만 사용합니다. TLS(HTTPS) 설정은 없습니다(후속 작업).
- 백엔드만 새로 띄우고 Nginx를 그대로 두면 Nginx가 이전 백엔드 주소로 요청을 보내 502가 날 수 있습니다(#77 리뷰). 그래서 CD는 백엔드 교체 뒤 Nginx를 강제로 다시 만듭니다. 서버에서 손으로 백엔드를 교체했다면 Nginx도 다시 만드세요.

```bash
docker compose -p ubot -f docker-compose.yml -f docker-compose.deploy.yml up -d --no-deps --force-recreate nginx
```
- `CHAT_RESPONSE_TIMEOUT_MILLIS`를 180초보다 크게 바꾸면 `nginx.conf`의 `proxy_read_timeout`도 함께 늘려야 합니다.
- DB 마이그레이션은 `backend` 기동 시 자동 적용됩니다. 되돌릴 수 없는 마이그레이션이 포함된 배포는 이미지 롤백만으로 DB가 되돌아가지 않습니다.
- DB 데이터와 Ollama 모델은 Compose 볼륨에 저장됩니다. `docker compose down -v`는 둘 다 삭제합니다.
