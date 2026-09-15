# UBot-BE

통신사 고객 상담용 RAG 챗봇 UBot의 백엔드 서버입니다.

- 스택: Spring Boot 4.1.1 · Java 21 · PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4 · Ollama(BGE-M3)
- 대상: 이 저장소를 개발하는 팀원

## Quick start

요구사항: JDK 17 이상, 실행 중인 Docker Desktop, Git. Java 21이 없으면 첫 Gradle 실행 때 자동으로 내려받습니다. Ollama는 Compose 컨테이너로 실행하므로 호스트에 별도 설치하지 않아도 됩니다.

새로 받은 저장소에서 실행합니다. 기존 `.env`가 있으면 덮어쓰지 마세요.

```powershell
Copy-Item .env.example .env     # POSTGRES_PASSWORD를 긴 임의의 영문·숫자 값으로 수정
docker compose up -d --build
docker compose ps -a
docker compose logs --tail=30 ollama-init
docker compose exec ollama ollama list
.\gradlew.bat bootRun
```

`postgres`와 `ollama`가 healthy이고, `ollama-init`이 `Exited (0)`이며 모델 목록에 `bge-m3:567m`이 있는지 확인한 뒤 `bootRun`을 실행하세요. 첫 모델 다운로드는 시간이 걸릴 수 있습니다. 기본 호스트 포트는 PostgreSQL `15432`, Ollama `11435`, 애플리케이션 `8080`입니다.

현재 `.env`의 dotenv/Java properties 파싱 차이는 아직 해결하지 않았습니다. 비밀번호를 따옴표 없이 영문·숫자로 작성하는 것은 임시 회피책입니다. [설정 제약](docs/reference/configuration.md#env-파싱-제약-아직-미해결)을 먼저 확인하세요.

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

Testcontainers가 테스트 전용 PostgreSQL + pgvector + PostGIS 컨테이너를 만들고, Flyway V1·V2를 실행한 뒤 종료 시 정리합니다. 개발 DB의 데이터나 Compose 볼륨은 사용하지 않습니다. 첫 실행에는 DB 이미지 빌드와 패키지 다운로드가 필요할 수 있습니다.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 처음 로컬에서 실행하기 | [docs/quickstart.md](docs/quickstart.md) |
| DB 직접 조회하기 (psql, DBeaver) | [docs/how-to/db-access.md](docs/how-to/db-access.md) |
| 환경변수·프로필 설정값 찾기 | [docs/reference/configuration.md](docs/reference/configuration.md) |
| 실행이 안 될 때 | [docs/troubleshooting.md](docs/troubleshooting.md) |
| 브랜치·PR·CI 규칙 | [CONTRIBUTING.md](CONTRIBUTING.md) |

## 호환성

| 구성요소 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| PostgreSQL + pgvector + PostGIS | 18 + 0.8.6 + 3.6.4 (`infra/postgres/Dockerfile`) |
| Ollama | `ollama/ollama:0.34.0` |
| Embedding 모델 | `bge-m3:567m` (1024차원) |
