# UBot-BE

**UBot**은 통신사 고객이 요금제, 가입, 로밍, 매장 같은 궁금증을 대화하듯 물어보면, 등록된 FAQ를 근거로 답해 주는 상담 챗봇입니다. 이 저장소는 그 백엔드 서버입니다.

- 프론트엔드: [UBot-FE](https://github.com/ureca-UBot/UBot-FE)
- 대상 독자: 이 저장소를 개발하는 팀원

## UBot이 하는 일

고객이 질문하면 UBot은 FAQ 중에서 가장 비슷한 것을 찾아, 그 내용만을 근거로 답변을 만듭니다. 근거가 없으면 지어내지 않고 "정확한 답변을 찾지 못했습니다"라고 답합니다.

```text
질문 → 금지어 검사 → FAQ 벡터 검색(상위 3건) → 유사도 0.75 이상만 통과 → LLM 답변 생성
                                              └ 통과한 FAQ가 없으면 미응답 질문으로 저장
```

![채팅 질문 처리 흐름](docs/images/chat-flow.svg)

자세한 흐름은 [architecture.md](docs/architecture.md#챗봇-질문-처리-흐름)에 있습니다.

## 주요 기능

### 고객용

| 기능 | 설명 |
|---|---|
| **AI 상담 챗봇** | FAQ를 근거로 답변을 생성합니다. 답변 생성에 실패하면 같은 질문을 최대 3회까지 다시 시도할 수 있습니다. |
| **비회원(게스트) 상담** | 로그인하지 않아도 질문할 수 있습니다. 세션(30분)으로 식별하고, 질문 횟수는 관리자 설정값(기본 5회)까지입니다. |
| **회원 기능** | 회원가입, 로그인, 토큰 재발급, 내 정보 조회·수정을 제공합니다. |
| **매장 찾기** | 지역·지도 범위·현재 위치로 매장을 찾고, 도보·자동차·대중교통 길찾기를 제공합니다. |

### 관리자용

| 기능 | 설명 |
|---|---|
| **FAQ 관리** | FAQ와 카테고리를 등록·수정·삭제·복구합니다. 수정 이력과, 어떤 답변에 어떤 FAQ가 쓰였는지 볼 수 있습니다. |
| **미응답 질문 관리** | 챗봇이 답하지 못한 질문을 비슷한 것끼리 묶어 보여 주고, 묶음에서 바로 FAQ를 만들 수 있습니다. |
| **금지어 관리** | 금지어가 들어간 질문은 검색과 답변 생성 전에 차단합니다. |
| **매장 관리** | 매장을 등록·수정·삭제·복구합니다. |

API 경로와 인증 방식은 [architecture.md](docs/architecture.md)에, 오류 코드는 [error-codes.md](docs/reference/error-codes.md)에 있습니다.

## 기술 구성

| 영역 | 사용 기술 |
|---|---|
| 서버 | Java 21, Spring Boot 4.1.1, Spring Security + JWT |
| 데이터 | PostgreSQL 18 (pgvector로 FAQ 벡터 검색, PostGIS로 매장 위치 검색), Flyway, Redis |
| AI | Ollama — 임베딩 `bge-m3:567m`(1024차원), 채팅 모델은 환경변수로 지정 |
| 외부 API | 카카오 로컬(주소 검색)·길찾기, 카카오모빌리티 |
| 배포 | Docker Compose, Nginx (`/api` 접두사 처리, 게스트 채팅 속도 제한) |

설정값 전체는 [configuration.md](docs/reference/configuration.md)에 있습니다.

## 바로 실행해 보기

JDK 17 이상, 실행 중인 Docker Desktop, Git이 필요합니다.

```powershell
Copy-Item .env.example .env     # POSTGRES_PASSWORD와 JWT_SECRET을 반드시 수정
docker compose up -d --build
.\gradlew.bat bootRun
```

`http://localhost:8080/actuator/health`가 `"status":"UP"`이면 실행된 것입니다. API는 Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 볼 수 있습니다.

- `JWT_SECRET`을 바꾸지 않으면 기동되지 않습니다([quickstart](docs/quickstart.md#2-env-만들기)).
- 챗봇 답변까지 보려면 채팅 모델과 FAQ 데이터가 필요합니다([local-data.md](docs/how-to/local-data.md)).

| 선택 사항 | 명령 |
|---|---|
| 프론트와 백엔드를 같은 Nginx로 제공 (`http://localhost:8088`) | `docker compose -f docker-compose.yml -f docker-compose.web.yml up -d web` |

테스트는 Docker만 실행 중이면 됩니다.

```powershell
.\gradlew.bat test
```

## 더 알아보기

| 궁금한 것 | 문서 |
|---|---|
| 처음부터 단계별로 실행하기 | [quickstart.md](docs/quickstart.md) |
| 코드 구조, API, 요청 흐름 | [architecture.md](docs/architecture.md) |
| 환경변수와 설정값 | [configuration.md](docs/reference/configuration.md) |
| 오류 코드 | [error-codes.md](docs/reference/error-codes.md) |
| 실행이 안 될 때 | [troubleshooting.md](docs/troubleshooting.md) |
| DB 조회, ERD, 관리자 계정 만들기 | [db-access.md](docs/how-to/db-access.md) |
| 로컬 FAQ 데이터와 채팅 모델 준비 | [local-data.md](docs/how-to/local-data.md) |
| 프론트엔드 연동 | [frontend-integration.md](docs/how-to/frontend-integration.md) |
| LLM 호출과 프롬프트 | [llm-module.md](docs/how-to/llm-module.md) |
| 배포 | [deploy.md](docs/deploy.md) |
| 브랜치·PR·커밋 규칙 | [CONTRIBUTING.md](CONTRIBUTING.md) |
| 유사도 기준 0.75를 정한 근거 | [FAQ_Threshold_테스트_보고서.md](docs/FAQ_Threshold_테스트_보고서.md) |
