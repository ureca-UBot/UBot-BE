# UBot-BE

통신사 고객 상담용 RAG 챗봇 UBot의 백엔드 서버입니다.

- 대상: 이 저장소를 개발하는 팀원
- 저장소: https://github.com/ureca-UBot/UBot-BE

> 문서 기준: UBot-BE `develop` [`37bc033`](https://github.com/ureca-UBot/UBot-BE/commit/37bc033a1439c23cf5c586d8acb4455fb0130be2) (2026-09-29 15:01 KST 커밋, #104 병합 시점) · 작성일 2026-09-29

## 스택

| 구성요소 | 버전 / 비고 |
|---|---|
| Java | 21 (Gradle toolchain) |
| Spring Boot | 4.1.1 |
| 인증 | Spring Security + JWT (jjwt 0.13.0) |
| DB 스키마 | Flyway (`V1`~`V14`) |
| DB | PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4 (`infra/postgres/Dockerfile`) |
| 임베딩 | Ollama `bge-m3:567m` (1024차원), `EmbeddingService`가 REST로 직접 호출 |
| LLM | Ollama 채팅 모델 (`OLLAMA_CHAT_MODEL`로 지정, 직접 `pull` 필요) |
| Spring AI | 2.0.1 (chat만 사용, embedding·vectorstore는 현재 비활성) |
| Ollama | `ollama/ollama:0.34.0` (Compose 컨테이너) |
| 외부 API | 카카오 로컬(주소 검색)·길찾기(도보·대중교통), 카카오모빌리티(자동차) |
| API 문서 | springdoc-openapi 3.1.1 (Swagger UI) |

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

기동 후 Swagger UI는 `http://localhost:8080/swagger-ui.html`에서 볼 수 있습니다. 챗봇까지 확인하려면 채팅 모델과 FAQ 데이터가 추가로 필요합니다([local-data.md](docs/how-to/local-data.md)). 단계별 설명은 [docs/quickstart.md](docs/quickstart.md)를 참고하세요.

## 테스트

JDK 17 이상과 실행 중인 Docker가 있으면 아래 명령만 실행하면 됩니다. 개발용 `.env`와 `docker compose up`은 필요하지 않습니다.

```powershell
.\gradlew.bat test
```

Testcontainers가 Compose와 같은 Dockerfile로 테스트 전용 DB 컨테이너를 만들고, Flyway를 실행한 뒤 종료 시 정리합니다. 개발 DB의 데이터나 Compose 볼륨은 사용하지 않습니다.

> 로컬에서는 실제 Ollama가 필요한 분석 테스트 하나가 함께 실행됩니다. Ollama 없이 돌리는 방법은 [troubleshooting](docs/troubleshooting.md#로컬-테스트에서-intentclassificationanalysis가-실패함)을 참고하세요.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 처음 로컬에서 실행하기 | [docs/quickstart.md](docs/quickstart.md) |
| 코드 구조·API·요청 흐름 파악하기 | [docs/architecture.md](docs/architecture.md) |
| 환경변수·프로필 설정값 찾기 | [docs/reference/configuration.md](docs/reference/configuration.md) |
| DB 직접 조회하기 (psql, DBeaver), ERD, 로컬 관리자 계정 | [docs/how-to/db-access.md](docs/how-to/db-access.md) |
| 로컬 테스트 데이터(FAQ) 준비하기 | [docs/how-to/local-data.md](docs/how-to/local-data.md) |
| 프론트엔드 연동 (인증·채팅·배포 환경) | [docs/how-to/frontend-integration.md](docs/how-to/frontend-integration.md) |
| LLM 호출·프롬프트 연결 방식 | [docs/how-to/llm-module.md](docs/how-to/llm-module.md) |
| 전체 오류 코드 | [docs/reference/error-codes.md](docs/reference/error-codes.md) |
| 실행이 안 될 때 | [docs/troubleshooting.md](docs/troubleshooting.md) |
| 배포 (Docker 이미지, Compose, Nginx, 수동 CD) | [docs/deploy.md](docs/deploy.md) |
| 브랜치·PR·CI·Flyway 규칙, 코딩 컨벤션 | [CONTRIBUTING.md](CONTRIBUTING.md) |
| FAQ 유사도 임계값 측정 결과 | [docs/FAQ_Threshold_테스트_보고서.md](docs/FAQ_Threshold_테스트_보고서.md) |

## 현재 구현 범위

- **인증·회원**: 회원가입, 로그인, refresh token 재발급, 로그아웃, 내 정보 조회·수정 (`/auth/**`, `/auth/me`)
- **챗봇**: 금지어 검사 → 질문 임베딩 → FAQ 벡터 유사도 검색(Top-K, 임계값 필터) → 프롬프트 구성 → Ollama LLM 답변 생성. 시도별 상태(`PENDING`/`SUCCESS`/`FAIL`)를 기록하고 실패 시 최대 3회까지 재시도할 수 있습니다. ([LLM 모듈 안내](docs/how-to/llm-module.md))
- **관리자** (`/admin/**`, `ADMIN` 역할): FAQ·카테고리 CRUD, 삭제 FAQ 복구, FAQ 수정 이력, 답변 참고 로그 조회, 금지어 관리, 매장 등록·수정·삭제·복구
- **매장**: 목록·상세·근처·지도·지도 클러스터·시도/시군구 목록 조회 (PostGIS), 매장까지 길찾기(도보·자동차·대중교통)
- **위치**: 카카오 로컬 API 기반 주소·좌표 검색

로그인·회원가입·토큰 재발급, 매장·위치 조회, health, Swagger를 제외한 요청은 JWT 인증이 필요합니다. 정확한 경로 목록은 [architecture.md#인증](docs/architecture.md#인증)에 있습니다.

자세한 흐름, 후속 작업, 진행 중인 PR·이슈(미응답 질문 저장 #105, 응답 캐시 #103 등)는 [docs/architecture.md](docs/architecture.md)에 있습니다.
