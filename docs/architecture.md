# 코드 구조와 요청 흐름

> 문서 기준 시점: 2026-09-22 (`develop` = `04c5a6c`, LLM 채팅 연결 반영, 열린 PR #55 미반영)

## 패키지 구조

```text
com.ubot
├── auth       인증 (JWT 발급·검증, refresh token, Security 설정)
├── user       사용자 엔티티·저장소·서비스
├── chat       질문 처리 진입점 (컨트롤러 → ChatService)
├── ai         FAQ 프롬프트 구성과 LLM 호출 연결 (AiService)
├── prompt     질문·FAQ를 프롬프트 템플릿에 반영 (PromptService)
├── llm        LLM 요청 검증·Ollama 호출·오류 변환
├── embedding  Ollama /api/embed 직접 호출 (EmbeddingService)
├── faq        FAQ·카테고리 CRUD, 이력·로그 조회, FAQ 벡터 검색
├── location   카카오 로컬 API 연동 (주소·좌표 검색)
├── store      매장 조회 (목록·상세·근처·지도 클러스터, PostGIS)
└── common     공통 응답·에러코드·전역 예외 처리
```

| 종류 | 위치 |
|---|---|
| DB 마이그레이션 | `src/main/resources/db/migration/V1`~`V11` |
| 매장 조회 SQL | `src/main/resources/sql/store/*.sql` |
| DB 이미지 | `infra/postgres/Dockerfile` |
| 개발용 컨테이너 | `docker-compose.yml` |
| 배포용 | `Dockerfile`, `docker-compose.deploy.yml` ([deploy.md](deploy.md)) |

## 인증

- 세션을 쓰지 않는 stateless 구조입니다(`SecurityConfig`).
- 인증 없이 호출할 수 있는 경로는 `/auth/login`, `/auth/signup`, `/auth/refresh`, `/actuator/health` 네 개입니다. 나머지는 모두 JWT가 필요합니다.
- `JwtAuthenticationFilter`가 요청의 `Authorization: Bearer <token>`을 검증합니다.
- Access token 서명 키는 `JWT_SECRET`(Base64, 32바이트 이상)이며 기동 시점에 디코딩합니다. 값이 올바르지 않으면 애플리케이션이 뜨지 않습니다.
- 비밀번호는 BCrypt로 저장합니다.

## 챗봇 질문 처리 흐름

```text
POST /chat/questions
  → ChatService
      → FaqVectorService.getSimilarList(question, TOP_K)
          → EmbeddingService  : 질문을 Ollama /api/embed로 보내 1024차원 벡터 생성
          → FaqVectorRepository : pgvector 유사도 검색 (JdbcTemplate + PGvector)
      → 가장 유사한 결과의 similarityScore 판정
          ≥ 임계값 → AiService → PromptService → LlmService → OllamaClient
                     → 생성된 답변 반환 (프롬프트·모델 오류 시 실패 응답)
          < 임계값 → 실패 응답 반환
```

현재 상태와 한계는 다음과 같습니다.

- `TOP_K = 3`, 임계값 `0.75`는 **코드에 상수로 박혀 있는 임시값**입니다(`ChatService`의 TODO).
- 임계값을 넘으면 원래 질문과 검색된 FAQ 목록을 LLM에 전달해 답변을 생성합니다. `LlmConfig`가 구성한 전용 Spring AI `OllamaChatModel` 인스턴스를 사용합니다.
- 기본 FAQ 프롬프트 두 파일은 최종 문구를 기다리며 비워 두었습니다. 준비되기 전에는 모델을 호출하지 않고 실패 응답을 반환합니다. 설정과 호출 계약은 [LLM 모듈 연결 안내](how-to/llm-module.md)를 참고하세요.
- 질문 로그 저장, 미응답 질문 클러스터링은 TODO로 남아 있습니다.

## Spring AI를 쓰는 범위

Spring AI 의존성은 있지만 임베딩·벡터 저장은 직접 구현한 코드를 사용합니다.

| 기능 | 현재 구현 | 설정 |
|---|---|---|
| 임베딩 생성 | `EmbeddingService` (RestClient로 Ollama 직접 호출) | `spring.ai.model.embedding: none` |
| 벡터 검색 | `FaqVectorRepository` (JdbcTemplate + `PGvector`) | `spring.ai.vectorstore.type: none` |
| LLM 채팅 | `AiService` → `PromptService` → `LlmService` → `OllamaClient` (Spring AI) | `LlmConfig`: 서버 주소·모델명·LLM 전용 제한 시간 |

`spring.ai.model.embedding: none`으로 `OllamaEmbeddingModel` 빈을 만들지 않기 때문에, 그 빈에 의존하는 `PgVectorStore`도 생성되지 않습니다. 그래서 Spring AI가 `vector_store` 테이블을 자동으로 만들지 않습니다. `application-local.yml`의 pgvector 설정은 주석으로 남아 있고, Spring AI 벡터 스토어 채택이 확정되면 되살릴 예정입니다. 자세한 설정값은 [configuration.md](reference/configuration.md)를 참고하세요.

`EmbeddingService`는 `spring.ai.ollama`와 별개인 `ollama.*` 설정(`base-url`, `embedding.model`, `connect-timeout`, `read-timeout`)을 직접 읽습니다. 같은 환경변수를 두 곳에서 각각 읽는 구조입니다.

## 테스트 구성

- `UbotBeApplicationTests`: 테스트 전용 DB 연결, Flyway `V1`·`V2`, `vector`·`postgis` 버전, PostGIS 좌표계, pgvector 스키마(1024·HNSW·cosine), 벡터 저장·검색
- 도메인 테스트: `auth`(통합), `chat`, `ai`, `prompt`, `llm`, `store`(컨트롤러·서비스·저장소), `faq`(서비스·벡터 저장소), `location`, `embedding`
- LLM 관련 테스트는 모의 모델과 로컬 HTTP 서버로 호출 흐름·요청 검증·오류 처리를 확인합니다. 실제 Ollama 모델의 답변 품질을 검증하지는 않습니다.
- 테스트 프로필에서는 Ollama 자동 구성을 끄고 결정적인 테스트용 임베딩 구현을 사용합니다. 실제 BGE-M3 품질이나 Ollama 연결은 검증하지 않습니다.

## 진행 중인 작업 (열린 PR)

이 문서를 쓴 시점(2026-09-22)에 `develop`에 아직 들어오지 않은 PR입니다. merge되면 구조가 아래처럼 늘어납니다.

| PR | 내용 | 추가되는 것 |
|---|---|---|
| [#55](https://github.com/ureca-UBot/UBot-BE/pull/55) | 관리자 매장 관리 API | `AdminStoreController`, `AdminStoreService`, `Store`·`ServiceType` JPA 엔티티, 매장 중복·복구 정책, 지도 거리·클러스터링 개선 |

FAQ CRUD [#34](https://github.com/ureca-UBot/UBot-BE/pull/34)는 반영되었습니다. `AdminFaqController`, FAQ 관련 엔티티·서비스와 `faq/dto/response`로의 DTO 이동을 포함하며, LLM 연결 코드도 변경된 DTO 위치를 사용합니다.

#55도 `SecurityConfig`와 `ErrorCode`를 수정하므로, 병합 전 최신 `develop`을 반영하고 충돌 여부를 확인해야 합니다.
