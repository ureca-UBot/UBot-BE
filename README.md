# UBot-BE

통신사 고객 상담용 RAG 챗봇 UBot의 백엔드 서버입니다.

- 대상: 이 저장소를 개발하는 팀원
- 저장소: https://github.com/ureca-UBot/UBot-BE

> 문서 기준 시점: 2026-09-22 (`develop` = `1ac3bf2`)

## 스택

| 구성요소 | 버전 / 비고 |
|---|---|
| Java | 21 (Gradle toolchain) |
| Spring Boot | 4.1.1 |
| 인증 | Spring Security + JWT (jjwt 0.13.0) |
| DB 스키마 | Flyway (`V1`~`V11`) |
| DB | PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4 (`infra/postgres/Dockerfile`) |
| 임베딩 | Ollama `bge-m3:567m` (1024차원), `EmbeddingService`가 REST로 직접 호출 |
| Spring AI | 2.0.1 (chat만 사용, embedding·vectorstore는 현재 비활성) |
| Ollama | `ollama/ollama:0.34.0` (Compose 컨테이너) |

## Quick start

요구사항: JDK 17 이상, 실행 중인 Docker Desktop, Git. Java 21이 없으면 첫 Gradle 실행 때 자동으로 내려받습니다. Ollama는 Compose 컨테이너로 실행하므로 호스트에 별도 설치하지 않아도 됩니다.

```powershell
Copy-Item .env.example .env     # POSTGRES_PASSWORD와 JWT_SECRET을 반드시 수정
docker compose up -d --build
docker compose exec ollama ollama list
.\gradlew.bat bootRun
```

> **`.env`의 `JWT_SECRET`을 바꾸지 않으면 애플리케이션이 기동되지 않습니다.** 기본값이 안내 문구라 Base64 디코딩에 실패합니다. 생성 방법은 [quickstart](docs/quickstart.md#2-env-만들기)에 있습니다.

`postgres`와 `ollama`가 healthy이고, `ollama-init`이 `Exited (0)`이며 모델 목록에 `bge-m3:567m`이 있는지 확인한 뒤 `bootRun`을 실행하세요. 기본 호스트 포트는 PostgreSQL `15432`, Ollama `11435`, 애플리케이션 `8080`입니다.

정상 동작 확인:

```powershell
curl.exe http://localhost:8080/actuator/health
# 기대 결과: "status":"UP", components.db.status도 "UP"
```

단계별 설명은 [docs/quickstart.md](docs/quickstart.md)를 참고하세요.

## 테스트

JDK 17 이상과 실행 중인 Docker가 있으면 아래 명령만 실행하면 됩니다. 개발용 `.env`, `docker compose up`, Ollama는 필요하지 않습니다.

```powershell
.\gradlew.bat test
```

Testcontainers가 Compose와 같은 Dockerfile로 테스트 전용 DB 컨테이너를 만들고, Flyway를 실행한 뒤 종료 시 정리합니다. 개발 DB의 데이터나 Compose 볼륨은 사용하지 않습니다.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 처음 로컬에서 실행하기 | [docs/quickstart.md](docs/quickstart.md) |
| 코드 구조와 요청 흐름 파악하기 | [docs/architecture.md](docs/architecture.md) |
| 환경변수·프로필 설정값 찾기 | [docs/reference/configuration.md](docs/reference/configuration.md) |
| DB 직접 조회하기 (psql, DBeaver) | [docs/how-to/db-access.md](docs/how-to/db-access.md) |
| 실행이 안 될 때 | [docs/troubleshooting.md](docs/troubleshooting.md) |
| 배포 (Docker 이미지, Compose) | [docs/deploy.md](docs/deploy.md) |
| 브랜치·PR·CI·Flyway 규칙 | [CONTRIBUTING.md](CONTRIBUTING.md) |

## 현재 구현 범위

- 인증: 회원가입, 로그인, refresh token (`/auth/**`)
- 매장: 목록·상세·근처·지도 클러스터 조회 (PostGIS)
- 위치: 카카오 로컬 API 기반 주소·좌표 검색
- 챗봇: 질문 임베딩 → FAQ 벡터 유사도 검색 → 프롬프트 구성 → Ollama LLM 답변 생성 (최종 프롬프트 문구 작성 대기, [LLM 모듈 안내](docs/how-to/llm-module.md))

`/auth/login`, `/auth/signup`, `/auth/refresh`, `/actuator/health`를 제외한 모든 요청에는 JWT 인증이 필요합니다. 자세한 흐름과 진행 중인 작업은 [docs/architecture.md](docs/architecture.md)에 있습니다.
