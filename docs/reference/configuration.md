# 설정 Reference

> 문서 기준: UBot-BE `develop` [`2fdb6ec`](https://github.com/ureca-UBot/UBot-BE/commit/2fdb6ec145d6092696651d83e4b6ff01ffb821c0) (2026-09-29 19:55 KST 커밋, #105 병합 시점) · 작성일 2026-09-30

## 설정 파일

```text
src/main/resources/
├── application.yml         # 모든 환경 공통 (Flyway, JWT, actuator, AI 실행 엔진)
├── application-local.yml   # 로컬 개발 (기본 프로필)
├── application-prod.yml    # 운영
└── application-test.yml    # 로컬·CI의 자동 테스트
```

| 프로필 | 사용 환경 | 활성화 방법 | 접속 정보 공급원 |
|---|---|---|---|
| `local` | 개발자 PC (`bootRun`, Eclipse) | 기본값 (`spring.profiles.default: local`) | 프로젝트 루트의 `.env` 또는 OS 환경변수 |
| `prod` | 운영 서버 | OS 환경변수 `SPRING_PROFILES_ACTIVE=prod` | 컨테이너에 주입된 환경변수. `.env` 파일을 직접 읽지 않음 |
| `test` | 로컬·GitHub Actions의 자동 테스트 | `gradlew test`는 `build.gradle`에서 `spring.profiles.active=test`를 강제. IDE에서 JUnit을 직접 실행하면 테스트 클래스의 `@ActiveProfiles("test")`가 적용 | Testcontainers + `@ServiceConnection` |

자동 테스트는 별도 DB 컨테이너의 임의 호스트 포트와 접속 정보를 사용합니다. 개발 `.env`, 고정 포트, 개발 DB·볼륨을 공유하지 않습니다. `test` 프로필만 지정해 `bootRun`을 실행하는 것은 Testcontainers 기반 테스트 실행과 다릅니다.

`prod`는 `local`과 달리 접속 정보에 기본값이 없고(`POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `REDIS_HOST` 필수), health 상세를 숨기고, Swagger UI와 API 문서를 끄고, 종료할 때 처리 중인 요청을 최대 30초 기다립니다. 배포된 백엔드가 이 프로필로 실행됩니다. 배포 스크립트는 서버 `.env`의 `SPRING_PROFILES_ACTIVE`가 `prod`가 아니면 배포하지 않습니다([deploy.md](../deploy.md#서버의-env)).

## 실행 환경 (Docker Compose)

개발용 컨테이너는 공통 파일에 모델 서버 파일 하나를 더해서 띄웁니다. 어느 파일을 쓸지는 `.env`의 `AI_MODE`가 정하고, `tools/ubot.ps1`이 그 값에 맞는 파일을 골라 줍니다. 실행 순서는 [quickstart](../quickstart.md#실행-환경-선택)에 있습니다.

| 파일 | 서비스 | 쓰는 경우 |
|---|---|---|
| `docker-compose.yml` | `postgres`, `redis`, `prometheus` | 항상 |
| `docker-compose.ollama.yml` | `ollama`, `ollama-init` | `AI_MODE=ollama` (기본) |
| `docker-compose.ollama.gpu.yml` | `ollama`에 NVIDIA GPU 연결 | `AI_MODE=ollama`이고 `.env`의 `OLLAMA_GPU=true`일 때 `ubot.ps1 up`이 덧붙임. 배포 워크플로는 쓰지 않음 |
| `docker-compose.vllm.yml` | `vllm`(답변 생성), `vllm-embedding`(임베딩) | `AI_MODE=vllm`. NVIDIA GPU 필요 |

| 명령 | 하는 일 |
|---|---|
| `.\tools\ubot.ps1 up` | 다른 쪽 모델 서버 컨테이너를 내린 뒤, 공통 서비스와 `AI_MODE`에 맞는 모델 서버를 띄웁니다. `vllm`이면 두 서버의 `/v1/models`에 `LLM_MODEL`, `EMBEDDING_MODEL`이 보일 때까지 서버마다 최대 300초 기다립니다 |
| `.\tools\ubot.ps1 down` | 세 파일의 컨테이너를 모두 내립니다. 볼륨은 남깁니다 |
| `.\tools\ubot.ps1 status` | 세 파일의 컨테이너 상태를 보여 줍니다 |
| `.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>` | 실행 중인 백엔드에 임베딩 백필 API 두 개를 호출합니다. `AI_MODE`나 임베딩 모델을 바꾼 뒤에 씁니다([백필](../how-to/llm-module.md#백필)) |

- 스크립트는 `docker compose -f <공통 파일> -f <모델 서버 파일> --env-file .env ...`를 실행합니다. 직접 실행할 때도 `-f`로 두 파일을 함께 지정합니다. `-f` 없이 실행하면 `docker-compose.yml`만 읽습니다. 그래서 `up`은 공통 서비스만 띄우고, `logs ollama`나 `run ollama-init`처럼 모델 서버를 지정한 명령은 `no such service`로 실패합니다.
- 두 모드는 같은 Compose 프로젝트(폴더 이름을 따름. 보통 `ubot-be`)와 같은 PostgreSQL 볼륨을 씁니다. 한 DB에 두 서버의 벡터가 Profile별로 따로 저장됩니다([Embedding Profile](../how-to/llm-module.md#embedding-profile)).
- `.env`에 `AI_MODE`가 없으면 스크립트는 실행되지 않습니다. `AI_MODE=custom`도 지원하지 않으므로, 그때는 필요한 모델 서버를 직접 띄웁니다.
- `docker-compose.vllm.yml`은 `infra/llm/docker-compose.yml`의 서비스 정의를 `extends`로 가져옵니다. 이때 값은 `infra/llm/.env`가 아니라 루트 `.env`에서 읽습니다. 루트 `.env`에 없는 값은 `infra/llm/docker-compose.yml`의 기본값을 씁니다.
- 받아 둔 vLLM 모델은 `llm_hf-cache` 볼륨에 있습니다. `infra/llm`에서 따로 띄운 서버와 같은 볼륨이라 모델을 다시 받지 않습니다.
- 배포는 `-p ubot`과 `-f`로 프로젝트 이름과 파일을 직접 지정합니다([deploy.md](../deploy.md)).

## 개발 환경변수

`.env.example`을 복사했을 때의 값과 Spring YAML의 fallback을 구분합니다. fallback이 "없음"인 값은 `.env` 또는 OS 환경변수로 반드시 제공해야 하며, 없으면 기동 시점에 `Could not resolve placeholder` 오류가 납니다.

| 변수 | 사용처 | `.env.example` 값 | Spring fallback |
|---|---|---|---|
| `AI_MODE` | `AiRuntimeProperties`(`app.ai.mode`), `tools/ubot.ps1`, CD: 답변 생성과 임베딩에 쓸 모델 서버. `ollama`, `vllm`, `custom` | `ollama` | 키가 없으면 `ollama`. 빈 값이거나 다른 값이면 기동되지 않음 |
| `AI_CHAT_ENGINE` | `AiRuntimeProperties`: `AI_MODE=custom`일 때 답변 생성 엔진. `ollama` 또는 `vllm` | 주석 처리됨 | 빈 값. `custom`인데 비어 있거나 `custom`이 아닌데 값이 있으면 기동되지 않음 |
| `AI_EMBEDDING_ENGINE` | `AiRuntimeProperties`: `AI_MODE=custom`일 때 임베딩 엔진. `ollama` 또는 `vllm` | 주석 처리됨 | 빈 값. 조건은 `AI_CHAT_ENGINE`과 같음 |
| `EMBEDDING_PROFILE_VERSION` | `EmbeddingProfileService`: Embedding Profile의 버전. 엔진과 모델 이름이 그대로인데 벡터를 새로 만들어야 할 때 올림 | `1` | `1`. 1보다 작으면 기동되지 않음 |
| `AI_RUNTIME_MANAGED` | CD: 모델 서버를 배포 서버에 함께 띄울지 여부. `true` 또는 `false` ([deploy.md](../deploy.md#서버의-env)) | `true` | Spring에서 읽지 않음 |
| `POSTGRES_HOST` | Spring | `localhost` | `localhost` |
| `POSTGRES_PORT` | Docker, Spring | `15432` | `15432` |
| `POSTGRES_DB` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_USER` | Docker, Spring | `ubot` | `ubot` |
| `POSTGRES_PASSWORD` | Docker, Spring | `change-me` (**변경 필수**) | 없음 |
| `OLLAMA_PORT` | Docker의 호스트 포트 | `11435` | Spring에서 읽지 않음 |
| `OLLAMA_GPU` | `tools/ubot.ps1`: `true`면 Ollama 컨테이너에 NVIDIA GPU 연결 | `false` | Spring에서 읽지 않음. 줄이 없으면 `false` |
| `OLLAMA_BASE_URL` | Spring (`spring.ai.ollama`, `ollama`) | `http://localhost:11435` | `http://localhost:11435` |
| `OLLAMA_EMBEDDING_MODEL` | Spring, `ollama-init` | `bge-m3:567m` | `bge-m3:567m` |
| `OLLAMA_CHAT_MODEL` | Spring (`spring.ai.ollama.chat.model`), `LlmConfig`, `ollama-init`(값이 있으면 채팅 모델도 내려받음) | `qwen3:4b-instruct-2507-q4_K_M` (프롬프트와 함께 검증한 모델) | 빈 값. 줄이 없어도 기동되며 `LlmConfig`는 빈 값 허용 |
| `LLM_CONNECT_TIMEOUT` | LLM 전용 HTTP 연결 제한 시간 | `3s` | `LlmConfig` 기본값 `3s` |
| `LLM_READ_TIMEOUT` | LLM 전용 HTTP 응답 제한 시간 | `120s` | `LlmConfig` 기본값 `120s` |
| `LLM_MAX_CONCURRENT` | `LlmService`: 프로세스 하나가 모델 서버에 동시에 보내는 호출 수의 상한. 시간 초과로 끊긴 호출이 끝나기 전까지는 허가를 쥐고 있어, Worker가 슬롯을 먼저 돌려줘도 모델 서버에 걸리는 호출은 상한을 넘지 않음 | 비움(`CHAT_WORKER_CONCURRENCY`와 같음) | `app.chat.worker.concurrency` |
| `LLM_BASE_URL` | `LlmConfig`: 답변 생성 엔진이 `vllm`일 때 호출할 OpenAI 호환 API 주소(`/v1`까지) | `http://localhost:8000/v1` | `http://localhost:8000/v1` |
| `LLM_MODEL` | `LlmConfig`: 답변 생성 엔진이 `vllm`일 때 요청에 넣는 모델 이름. `LLM_SERVED_MODEL_NAME`과 같아야 함. `tools/ubot.ps1`의 준비 확인에도 사용 | `ubot-chat` | `ubot-chat` |
| `LLM_PORT` | Docker Compose: vLLM 답변 생성 서버의 호스트 포트. `tools/ubot.ps1`의 준비 확인에도 사용 | `8000` | Spring에서 읽지 않음 |
| `LLM_HF_MODEL` | Docker Compose: vLLM이 Hugging Face에서 불러올 답변 생성 모델 | `RedHatAI/Qwen3-4B-Instruct-2507-quantized.w4a16` (Qwen3-4B-Instruct-2507 GPTQ W4A16) | Spring에서 읽지 않음 |
| `LLM_SERVED_MODEL_NAME` | Docker Compose: vLLM이 API에 내보이는 답변 생성 모델 이름 | `ubot-chat` | Spring에서 읽지 않음 |
| `LLM_MAX_MODEL_LEN` | Docker Compose: vLLM 답변 생성 서버가 받는 최대 토큰 길이(`--max-model-len`) | `4096` | Spring에서 읽지 않음 |
| `VLLM_GPU_MEMORY_UTILIZATION` | Docker Compose: vLLM 답변 생성 서버가 쓸 GPU 메모리 비율 | `0.6` | Spring에서 읽지 않음 |
| `VLLM_TOOL_CALL_PARSER` | Docker Compose: vLLM 답변 생성 서버의 tool call parser | `hermes` | Spring에서 읽지 않음 |
| `VLLM_ENABLE_THINKING` | Docker Compose: vLLM 답변 생성 서버의 thinking 모드 기본값 | `false` | Spring에서 읽지 않음 |
| `CHAT_MAX_ATTEMPTS` | `ChatAttemptsService`: 최초 요청을 포함한 최대 답변 생성 시도 횟수 | `3` | `3` |
| `CHAT_TOP_K` | `ChatAnswerProcessor`: FAQ 검색 시 요청하는 최대 결과 개수 | `3` | `3` |
| `CHAT_CONFIDENCE_THRESHOLD` | `ChatAnswerProcessor`: 캐시 실패 후 하위 질문마다 FAQ 질문 벡터 검색 후보의 코사인 유사도 기준. 통과한 후보만 답변 문장 검사에 사용하고, 남은 FAQ가 없으면 그 하위 질문은 근거 없음으로 처리 | `0.75` | `0.75` |
| `FAQ_SENTENCE_SIMILARITY_THRESHOLD` | `FaqSentenceFilterService`: 질문 유사도 기준을 통과한 FAQ에서 하위 질문의 검색어와 각 답변 문장의 코사인 유사도 기준. 통과한 문장만 답변 LLM에 전달 | `0.7` | `0.7` |
| `CHAT_SUB_QUESTION_ENABLED` | `SubQuestionPlanner`: 판정 LLM으로 질문을 정리할지 여부. 켜면 질문 분리·멀티턴 표지가 있는 질문, 또는 원문 검색이 기준에 못 미친 질문만 판정 LLM이 이전 대화(최대 3턴)를 참고해 하위 질문(최대 3개)별 검색어를 만듦(LLM 호출이 한 번 늘어남). 검색어는 검색에만 쓰고, 답변 LLM에는 원문과 원문 조각, 멀티턴이면 이전 대화를 넘김. 끄면 원문 하나로 검색하고 이전 대화도 넘기지 않음 | `true` | 키가 없으면 `true` |
| `CHAT_SEARCH_FIRST_HISTORY_THRESHOLD` | `ChatAnswerProcessor`: 판정할 표지가 없는 질문을 원문으로 먼저 검색할 때, 이전 대화가 있으면 이 값 이상이어야 판정 없이 끝냄(이전 대화가 없으면 `CHAT_CONFIDENCE_THRESHOLD`). 기본값은 유사도 기준과 같고 튜닝용으로 따로 둠. `CHAT_CONFIDENCE_THRESHOLD`보다 낮으면 그 값을 씀 | `0.75` | 키가 없으면 `0.75` |
| `FAQ_RERANK_ENABLED` | 캐시 실패 후 질문 벡터 검색 후보를 Reranker로 다시 정렬할지 여부. 켜면 Reranker 서버가 떠 있어야 함. 전달 여부는 켜든 끄든 `CHAT_CONFIDENCE_THRESHOLD`(코사인 유사도)로 정하고, 정렬 뒤 답변 문장을 임베딩해 비교([Reranker 테스트 보고서](../FAQ_Reranker_테스트_보고서.md)) | `false` | 키가 없으면 `false` |
| `FAQ_RERANK_CANDIDATE_K` | `FaqRetrievalService`: Reranker를 켰을 때 벡터 검색에서 가져올 후보 수. 켠 상태에서 `CHAT_TOP_K`보다 작으면 기동 실패 | `10` | 키가 없으면 `10` |
| `FAQ_RERANK_MODEL` | `FaqRetrievalConfig`: Reranker 서버에 요청할 모델 이름. Compose의 `RERANKER_SERVED_MODEL_NAME`과 같아야 함 | `ubot-reranker` | 키가 없으면 `ubot-reranker` |
| `FAQ_RERANK_BASE_URL` | `FaqRetrievalConfig`: Reranker 서버 주소. 이 주소의 `/v1/rerank`를 호출 | `http://localhost:8002` | 키가 없으면 `http://localhost:8002` |
| `FAQ_RERANK_CONNECT_TIMEOUT` | `FaqRetrievalConfig`: Reranker 서버 연결 제한 시간 | `500ms` | 키가 없으면 `500ms` |
| `FAQ_RERANK_READ_TIMEOUT` | `FaqRetrievalConfig`: Reranker 응답 제한 시간. 넘으면 실패로 처리 | `1500ms` | 키가 없으면 `1500ms` |
| `FAQ_RERANK_FALLBACK_ENABLED` | `FaqRetrievalService`: Reranker를 쓰지 못했을 때 이미 받아 둔 벡터 검색 상위 `CHAT_TOP_K`개로 답할지 여부. `false`면 그 요청은 실패 | `true` | 키가 없으면 `true` |
| `FAQ_RERANK_MAX_CONCURRENT` | `FaqRetrievalService`: 동시에 나가는 Reranker 호출 수의 한도. 넘치는 요청은 기다리지 않고 벡터 검색 결과로 답함 | `32` | 키가 없으면 `32` |
| `FAQ_RERANK_FAILURE_THRESHOLD` | `FaqRetrievalService`: 연속으로 이만큼 실패하면 Reranker 호출을 건너뛰기 시작 | `5` | 키가 없으면 `5` |
| `FAQ_RERANK_FAILURE_COOLDOWN` | `FaqRetrievalService`: 연속 실패 뒤 Reranker를 호출하지 않는 시간. 지나면 한 건만 보내 보고 성공하면 재개 | `10s` | 키가 없으면 `10s` |
| `RERANKER_PORT` | Docker Compose: Reranker 서버의 호스트 포트. `reranker` 프로필로 띄울 때만 사용 | `8002` | Spring에서 읽지 않음 |
| `VLLM_RERANKER_GPU_MEMORY_UTILIZATION` | Docker Compose: Reranker 서버가 쓸 GPU 메모리 비율 | `0.2` | Spring에서 읽지 않음 |
| `APP_ROLE` | `AppRole`(`app.role`): 프로세스 역할. `api`는 접수·상태 조회와 나머지 API·스케줄러, `worker`는 답변 Worker만(랭킹·보관 기간 삭제 스케줄러 없음, HTTP는 `/actuator`만 받고 나머지는 `404 G-004`), `all`은 둘 다. 배포 Compose는 `backend=api`, `ai-worker=worker`로 정함 | `all` | `all`. 다른 값이면 기동되지 않음 |
| `CHAT_WORKER_CONCURRENCY` | `ChatJobWorker`: Worker 하나가 동시에 만드는 답변 수. 빈 슬롯이 있을 때만 스트림을 읽음 | `4` | `4` |
| `COUNSEL_EMBEDDING_WORKER_ENABLED` | `CounselEmbeddingWorker`: 상담 전용 Redis 큐의 임베딩만 처리. `worker/all` 역할에서 기본 실행하며 `api`에서는 실행하지 않음. 별도 스레드 1개이며 기존 채팅 큐·실행 풀·DB를 사용하지 않음. 필요하면 `false`로 비활성화 | `true` | `true` (`test` 프로필은 `false`) |
| `COUNSEL_ENABLED` | 기존 BE 상담 API·배정/미완료 메시지 복구 활성화 | `true` | `true` |
| `COUNSEL_EMBEDDING_TRANSPORT` | 상담 임베딩 경로. 기본 `worker`는 기존 ai-worker에 위임. 자동 HTTP 우회 없음 | `worker` | `worker` |
| `COUNSEL_SEED_ON_START` | 로컬 API/all 시작 시 50개 상담 예시 색인. CD에서는 API의 이 값을 false로 고정하고 워커 준비 후 일회성 프로세스의 실행 인자로 색인·준비 상태를 검증. 동일 프로필은 재색인하지 않음 | `false` | `false` |
| `--counsel.seed-only=true` (실행 인자) | `--counsel.seed-on-start=true`와 함께 사용. Flyway migrate·스케줄러 없이 실행하며, 미적용 마이그레이션은 종료 코드 3, 성공은 0, 실패는 1. CD는 코드 3이면 API·워커 교체와 FAQ 벡터 확인이 끝난 뒤 색인 | 지정하지 않음 | `false` |
| `--counsel.seed-check-only=true` (실행 인자) | 위 두 인자와 함께 워커 교체 전 사전 검사에 사용. 체크섬·미적용 마이그레이션만 확인하며 색인·워커 호출 없이 종료 | 지정하지 않음 | `false` |
| `COUNSEL_GUARD_MODEL` / `COUNSEL_GUARD_PROFILE` | 상담 벡터 모델명 / 불변 검색 프로필 | `ubot-embedding` / `insult-v1` | 동일 |
| `COUNSEL_BLOCK_THRESHOLD` | 상담 문장 코사인 유사도 후보 기준 | `0.95` | `0.95` |
| `CHAT_WORKER_READ_BLOCK` | 새 메시지가 없을 때 다음 읽기까지 쉬는 간격. 실행 슬롯은 반환함 | `2s` | `2s` |
| `CHAT_WORKER_CONSUMER_NAME` | `ChatJobWorker`: Redis Streams 그룹(`chat-workers`) 안의 Worker 이름. Worker마다 달라야 함. 빈 값으로 두면 기동 실패 | 주석 처리 | `HOSTNAME`(컨테이너 ID), 없으면 `local` |
| `CHAT_WORKER_MAX_QUEUE_WAIT` | `ChatJobWorker`: 접수 후 전체 실행·복구 기한. 실행·넘겨받기 마감은 이 기한을 넘지 않음. 만료 작업은 `CHAT-016`으로 처리하며 발행 표시는 이보다 1분 길게 둠 | `10m` | `10m` |
| `CHAT_WORKER_REPUBLISH_AFTER` | `ChatJobWorker`: 이 시간 넘게 대기 중인데 발행 표시가 없는 작업(발행 실패, Redis 데이터 유실)을 다시 발행. `CHAT_WORKER_MAX_QUEUE_WAIT`보다 짧아야 하며 아니면 기동 실패 | `30s` | `30s` |
| `CHAT_WORKER_HEARTBEAT_TIMEOUT` | 실행 중인 작업의 DB heartbeat가 이 시간 이상 없으면 Worker가 죽은 것으로 보고 메시지를 회수. heartbeat는 이 값의 3분의 1(최대 15초)마다 갱신. `CHAT_WORKER_RECLAIM_IDLE`보다 길 수 없음 | `1m` | `1m` |
| `CHAT_WORKER_RECLAIM_IDLE` | 이 시간 이상 ACK되지 않은 메시지는 작업 상태를 보지 않고 회수 시도(마지막 안전망). 메시지도 없이 heartbeat가 두 배 동안 없으면 실패 처리 | `5m` | `5m` |
| `CHAT_WORKER_MAX_DELIVERIES` | `ChatJobWorker`: 한 메시지를 Worker에 전달하는 최대 횟수. 다 쓰면 다시 실행하지 않고 `CHAT-015`로 실패 처리. 1 미만이면 기동 실패 | `3` | `3` |
| `CHAT_WORKER_RECOVERY_INTERVAL` | `ChatJobWorker`: 발행이 빠진 작업을 다시 발행하고, 방치된 메시지를 넘겨받고, 오래 기다리거나 멈춘 작업을 정리하는 간격 | `15s` | `15s` |
| `CHAT_WORKER_RESULT_TTL` | Redis 결과 캐시 TTL. DB 원본은 지도까지 보존함 | `24h` | `24h` |
| `CHAT_WORKER_EXECUTION_TIMEOUT` | 임베딩·도구·LLM·모델 복구 대기를 포함한 한 실행의 제한. 재시도마다 연장하지 않음. 전체 기한과 비교해 이른 시각에 CHAT-016으로 끝내고 늦은 결과 폐기 | `180s` | `180s` |
| `CHAT_WORKER_MODEL_RETRY_BACKOFF` | Worker의 vLLM 연결 실패·5xx 재시도 간격. 실행 기한·취소·heartbeat·같은 작업을 유지. Worker 밖·4xx·잘못된 응답·HTTP 타임아웃에는 적용하지 않음. 0 이하이면 기동 실패 | `5s` | `5s` |
| `CHAT_WORKER_SHUTDOWN_WAIT` | Worker 종료 시 실행 중인 작업을 기다리는 시간. 넘기면 대기열에 돌려놓고 다시 발행. 컨테이너 종료 유예 시간(`stop_grace_period`, ECS `stopTimeout`)보다 짧아야 함 | `20s` | `20s` |
| `CHAT_USER_RATE_LIMIT_ENABLED` | `ChatRateLimitService`: 회원 질문·재시도·의도 재검색 접수를 사용자 ID별로 제한할지 여부. Redis(`chat:rate:user:<사용자ID>`)에 고정 윈도로 세고, Redis 장애 시 제한 없이 접수 | `true` | `true` (`test` 프로필은 `false`) |
| `CHAT_USER_RATE_LIMIT_MAX` | `ChatRateLimitService`: 윈도 하나에 받는 회원 접수 수. 넘으면 시도·작업을 만들지 않고 `CHAT-026`(429). 1 미만이면 기동 실패 | `30` | `30` |
| `CHAT_USER_RATE_LIMIT_WINDOW` | `ChatRateLimitService`: 회원 접수를 세는 윈도 길이. 1초 미만이면 기동 실패 | `60s` | `60s` |
| `CHAT_JOB_READ_BUDGET` | `ChatJobReadExecutor`: 답변 작업 조회(`GET /chat/jobs/{id}`)의 인증 사용자 조회·비회원 대화 승계·상태 조회가 함께 쓰는 요청별 한도. 넘기거나 DB 접근이 실패하면 `CHAT-025`(503). 0 이하이면 기동 실패 | `1500ms` | `1500ms` |
| `CHAT_JOB_READ_MAX_CONCURRENCY` | `ChatJobReadExecutor`: 동시에 실행하는 작업 조회 수 상한. 취소에 반응하지 않는 JDBC 조회도 포함하며 넘치면 기다리지 않고 `CHAT-025`(503) | `8` | `8` |
| `DB_POOL_MAX_SIZE` | HikariCP(`spring.datasource.hikari.maximum-pool-size`): 프로세스 하나의 DB 연결 수. API와 Worker를 합쳐 PostgreSQL `max_connections`보다 작아야 함 | `10` | `10` |
| `SCHEDULER_POOL_SIZE` | `spring.task.scheduling.pool.size`: `@Scheduled` 작업을 동시에 실행할 스레드 수. 1이면 한 작업(보관 기간 삭제 재시도, LLM 호출 등)이 끝날 때까지 다른 예약 작업(Outbox 처리 등)이 기다림. 같은 작업은 겹쳐 실행되지 않음 | `4` | `4` |
| `METRICS_BACKLOG_REFRESH` | `EmbeddingBacklogMetrics`(api 역할): FAQ 벡터 적체(`faq_embeddings_missing`·`_stale`)와 미응답 outbox 적체를 DB에서 다시 세는 주기. 첫 집계는 기동 30초 뒤([운영 지표](metrics.md#embedding-lifecycle)) | `5m` | `5m` |
| `UNANSWERED_GROUP_THRESHOLD` | `UnansweredQuestionService`: 미응답 질문을 기존 묶음에 넣을지 정하는 묶음 중심 벡터와의 최소 코사인 유사도 | `0.6` | `0.6` |
| `OUTBOX_POLL_DELAY_MS` | `OutboxWorker`: 공용 대기열(`outbox_events`) 작업을 꺼내 처리하는 주기(밀리초, 이전 처리가 끝난 뒤부터) | `2000` | `2000` |
| `UNANSWERED_NAMING_INTERVAL_MINUTES` | `UnansweredGroupNamingScheduler`: 처리 대기·보류 미응답 묶음에 LLM으로 주제 이름과 추천 카테고리를 붙이는 주기(분). 이름이 없거나 질문 수가 이름 붙인 때의 2배 이상이 된 묶음을 한 번에 10개까지 처리 | `5` | `5` |
| `OLLAMA_CONNECT_TIMEOUT` | `EmbeddingConfig`: Ollama 임베딩 연결 제한 시간 | `3s` | `3s` |
| `OLLAMA_READ_TIMEOUT` | `EmbeddingConfig`: Ollama 임베딩 응답 제한 시간 | `10s` | `10s` |
| `EMBEDDING_BASE_URL` | `EmbeddingConfig`: 임베딩 엔진이 `vllm`일 때 호출할 OpenAI 호환 API 주소(`/v1`까지) | `http://localhost:8001/v1` | `http://localhost:8001/v1` |
| `EMBEDDING_MODEL` | `EmbeddingConfig`: 임베딩 엔진이 `vllm`일 때 요청에 넣는 모델 이름. `EMBEDDING_SERVED_MODEL_NAME`과 같아야 함. Embedding Profile의 모델 이름으로도 기록됨 | `ubot-embedding` | `ubot-embedding` |
| `EMBEDDING_PORT` | Docker Compose: vLLM 임베딩 서버의 호스트 포트. `tools/ubot.ps1`의 준비 확인에도 사용 | `8001` | Spring에서 읽지 않음 |
| `EMBEDDING_HF_MODEL` | Docker Compose: vLLM이 Hugging Face에서 불러올 임베딩 모델 | `BAAI/bge-m3` | Spring에서 읽지 않음 |
| `EMBEDDING_SERVED_MODEL_NAME` | Docker Compose: vLLM이 API에 내보이는 임베딩 모델 이름 | `ubot-embedding` | Spring에서 읽지 않음 |
| `EMBEDDING_MAX_MODEL_LEN` | Docker Compose: vLLM 임베딩 서버가 받는 최대 토큰 길이 | `8192` | Spring에서 읽지 않음 |
| `EMBEDDING_DTYPE` | Docker Compose: vLLM 임베딩 모델의 dtype | `float16` | Spring에서 읽지 않음 |
| `VLLM_EMBEDDING_GPU_MEMORY_UTILIZATION` | Docker Compose: vLLM 임베딩 서버가 쓸 GPU 메모리 비율 | `0.2` | Spring에서 읽지 않음 |
| `EMBEDDING_CONNECT_TIMEOUT` | `EmbeddingConfig`: vLLM 임베딩 연결 제한 시간 | `3s` | `3s` |
| `EMBEDDING_READ_TIMEOUT` | `EmbeddingConfig`: vLLM 임베딩 응답 제한 시간 | `30s` | `30s` |
| `HF_TOKEN` | Docker Compose: vLLM이 모델을 받을 때 쓰는 Hugging Face 토큰. 공개 모델만 쓰면 비워 둠 | 빈 값 | Spring에서 읽지 않음 |
| `VLLM_IMAGE` | Docker Compose: vLLM 이미지 | `vllm/vllm-openai:v0.31.0` | Spring에서 읽지 않음 |
| `BACKEND_IMAGE` | Docker Compose(`docker-compose.deploy.yml`): 백엔드 이미지. CD는 `ubot-be:<커밋 SHA>`로 덮어씀 | `ubot-be:local` | Spring에서 읽지 않음 |
| `KAKAO_REST_API_KEY` | `KakaoLocalClient`(주소 검색), `KakaoDirectionsClient`(길찾기) | 빈 값 | 없음 |
| `JWT_SECRET` | `JwtUtil` | 안내 문구 (**변경 필수**) | 없음 |
| `JWT_ACCESS_TOKEN_EXPIRATION_MILLIS` | `JwtUtil` | `600000` (10분) | `3600000` (1시간) |
| `JWT_REFRESH_TOKEN_EXPIRATION_DAYS` | `RefreshTokenService` | `14` | `14` |
| `JWT_REVOCATION_LOOKUP_TIMEOUT` | `AccessTokenRevocationService`: 로그아웃 기록(Redis) 조회 한도. 넘기면 Redis 연결 오류와 같이 경고 로그만 남기고 차단 없이 통과 | `200ms` | `200ms` |
| `CORS_ALLOWED_ORIGINS` | `SecurityConfig` | (빈 값) | (빈 값, 같은 origin만 허용) |
| `SPRING_PROFILES_ACTIVE` | OS 환경변수로 제공하면 프로필 선택. CD는 서버 `.env`의 이 값이 `prod`인지 검사 | `local` | `.env`에 적는 것만으로는 프로필을 바꾸지 못함 |
| `ALERTS_ENABLED` | `render-alert-config.sh`: 알림 규칙을 만들지 여부. `false`면 빈 규칙을 만들고 Alertmanager 설정과 Webhook secret을 지움([장애 알림](../how-to/alerting.md)) | `false` | 렌더러 기본 `false`. Spring에서 읽지 않음 |
| `ALERT_DISCORD_WEBHOOK_URL`, `ALERT_SLACK_WEBHOOK_URL` | `render-alert-config.sh`: Discord·Slack Webhook URL. 값을 넣은 채널만 Alertmanager 설정에 남고, URL은 `.generated/alertmanager/secrets/<채널>-webhook`(권한 600)에만 씀. 알림을 켜면 하나 이상 필수. Git에 올리지 않음 | 빈 값 | 없음 |
| `ALERT_WORKER_JOB` | `render-alert-config.sh`: Worker 지표를 내보내는 Prometheus job. 로컬은 백엔드가 Worker도 맡아 `ubot-backend` | `ubot-backend` | 렌더러 기본 `ubot-ai-worker` |
| `ALERT_BACKEND_DOWN_FOR`, `ALERT_WORKER_DOWN_FOR`, `ALERT_DEPENDENCY_DOWN_FOR` | `render-alert-config.sh`: Backend·Worker·PostgreSQL/Redis 장애로 보기까지 이어져야 하는 시간(`30s`·`1m`·`2h` 형태) | `1m` | `1m` |
| `ALERT_CHAT_QUEUE_OLDEST_SECONDS`, `ALERT_CHAT_QUEUE_FOR` | `render-alert-config.sh`: 가장 오래 기다린 Chat Job 대기 시간 기준(초)과 이어져야 하는 시간 | `120`, `2m` | `120`, `2m` |
| `ALERT_LLM_TIMEOUT_RATIO`, `ALERT_LLM_TIMEOUT_WINDOW`, `ALERT_LLM_TIMEOUT_FOR`, `ALERT_LLM_TIMEOUT_MIN_CALLS` | `render-alert-config.sh`: LLM 시간 초과 비율 기준(0~1), 계산 기간, 이어져야 하는 시간, 계산에 필요한 최소 호출 수 | `0.05`, `5m`, `5m`, `10` | `0.05`, `5m`, `5m`, `10` |
| `ALERTMANAGER_PORT` | Docker Compose(`alerts` 프로필): Alertmanager 호스트 포트(127.0.0.1만) | `19093` | Spring에서 읽지 않음 |

Compose는 PostgreSQL `127.0.0.1:15432 → 5432`, Ollama `127.0.0.1:11435 → 11434`로 노출합니다. `OLLAMA_PORT`를 바꾸면 Spring이 사용하는 `OLLAMA_BASE_URL`의 포트도 함께 바꿔야 합니다.

`AI_MODE=vllm`에서는 Ollama 대신 답변 생성 서버를 `127.0.0.1:8000`, 임베딩 서버를 `127.0.0.1:8001`로 노출합니다. `LLM_PORT`, `EMBEDDING_PORT`를 바꾸면 `LLM_BASE_URL`, `EMBEDDING_BASE_URL`의 포트도 함께 바꿔야 합니다.

`LLM_MODEL`과 `LLM_SERVED_MODEL_NAME`, `EMBEDDING_MODEL`과 `EMBEDDING_SERVED_MODEL_NAME`은 각각 같은 값이어야 합니다. 앞의 것은 백엔드가 요청에 넣는 이름이고 뒤의 것은 vLLM이 내보이는 이름입니다. 불러올 실제 모델을 바꿀 때는 이 이름을 그대로 두고 `LLM_HF_MODEL`, `EMBEDDING_HF_MODEL`만 바꿉니다. 다만 임베딩 모델을 바꾸면 이름이 같아도 벡터가 달라지므로 `EMBEDDING_PROFILE_VERSION`을 올리고 백필합니다([Embedding Profile](../how-to/llm-module.md#embedding-profile)).

현재 채팅 경로는 `CHAT_CONFIDENCE_THRESHOLD`로 FAQ 질문 후보를 먼저 거르고, `FAQ_SENTENCE_SIMILARITY_THRESHOLD`로 그 FAQ의 답변 문장을 판단합니다.


`CHAT_MAX_ATTEMPTS`를 바꿔도 한도 초과 오류(`CHAT-006`)의 안내 문구는 "최대 3회"로 고정되어 있습니다.

### 환경변수 없이 코드 기본값만 있는 설정

아래 값은 YAML과 `.env.example`에 없고 코드의 기본값을 사용합니다. 바꾸려면 설정 키로 지정합니다.

| 설정 키 | 기본값 | 사용처 |
|---|---|---|
| `kakao.directions.connect-timeout` | `3s` | `KakaoDirectionsClient` |
| `kakao.directions.read-timeout` | `5s` | `KakaoDirectionsClient` |
| `prompt.faq.system-location` | `classpath:prompts/faq-system.txt` | `PromptService` |
| `prompt.faq.user-location` | `classpath:prompts/faq-user.txt` | `PromptService` |
| `spring.ai.ollama.chat.options.model` | 없음 (`OLLAMA_CHAT_MODEL`로 대체) | `LlmConfig` |
| `app.embedding-backfill.run` | `false` | `EmbeddingBackfillRunner`. `true`면 임베딩 백필만 실행하고 종료하며 DB 마이그레이션을 적용하지 않음. 배포 스크립트가 실행 인자로만 켬. `.env`에 넣지 않음 |

### 키가 있지만 값이 비어 있을 때

`KAKAO_REST_API_KEY`는 `.env.example`에서 빈 값입니다. `OLLAMA_CHAT_MODEL`을 비우는 경우도 같습니다. 빈 값이면 기동은 되지만 해당 기능이 실패합니다(채팅 모델 미지정, 카카오 API 인증 실패).

- `KAKAO_REST_API_KEY`는 키 줄 자체를 지우면 기동이 실패합니다.
- `OLLAMA_CHAT_MODEL`은 줄이 없어도 빈 값으로 처리되어 기동됩니다. 답변 생성 엔진이 `vllm`이면 이 값을 쓰지 않으므로 비워 둬도 됩니다.
- `AI_MODE`는 줄이 없으면 `ollama`로 기동되지만, `AI_MODE=`처럼 값만 비워 두면 기동이 실패합니다.

### `JWT_SECRET`은 반드시 교체해야 합니다

`JwtUtil`은 생성 시점에 이 값을 Base64로 디코딩해 HMAC 키를 만듭니다. `.env.example`의 기본값은 안내 문구여서 디코딩에 실패하고, 애플리케이션이 기동되지 않습니다.

```text
io.jsonwebtoken.io.DecodingException: Illegal base64 character: '-'
```

Base64로 인코딩된 32바이트 이상의 값이 필요합니다. 생성 방법은 [quickstart](../quickstart.md#2-env-만들기)에 있습니다.

### 값을 읽는 순서

- `local` 프로필은 `spring.config.import: optional:file:.env[.properties]`로 `.env`를 읽습니다.
- OS 환경변수가 `.env`보다 우선합니다. 나중에 실제 환경변수로 주입하면 코드 수정 없이 그 값이 사용됩니다.
- `.env`는 **실행 위치 기준 상대경로**로 찾습니다. 프로젝트 루트가 아닌 곳에서 실행하면 읽지 못합니다.
- Docker Compose도 같은 `.env`를 읽지만 **파싱 문법이 다르므로 같은 값을 읽는다고 보장할 수 없습니다.** 아래 제약을 확인하세요.

### `.env` 파싱 제약 (아직 미해결)

Docker Compose는 dotenv 문법을, Spring은 Java properties 문법을 사용합니다.

- 예를 들어 `POSTGRES_PASSWORD='example'`이면 Compose는 인용부호를 제거하지만 Spring은 인용부호를 비밀번호에 포함합니다.
- Spring Boot 4.1.1의 properties 로딩은 인코딩 미지정 시 ISO-8859-1을 사용하므로 UTF-8로 저장한 한글 등 비ASCII 값이 달라질 수 있습니다.
- `$`, 역슬래시, 따옴표, 공백, 인라인 주석도 두 파서에서 의미가 다를 수 있습니다.

당장은 **따옴표 없이 충분히 긴 임의의 영문·숫자 값**을 쓰고 값 뒤에 주석을 붙이지 않는 방식으로 충돌을 피하세요. 임시 회피책이며 근본 해결은 아직 적용하지 않았습니다. [Docker Compose의 dotenv 구문](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#env-file-syntax)도 참고하세요.

### `SPRING_PROFILES_ACTIVE`가 `.env`에서 효과 없는 이유

`.env`는 `local` 프로필 설정 안에서 읽히므로, 읽는 시점에는 이미 프로필이 결정되어 있습니다. 프로필을 바꾸려면 OS 환경변수나 실행 인자로 지정해야 합니다.

컨테이너로 띄울 때는 다릅니다. `docker-compose.deploy.yml`이 `.env`를 `env_file`로 주입하므로 그 안의 값이 실제 환경변수가 됩니다. 그래서 `.env`에 적은 프로필이 그대로 적용됩니다.

## DB 스키마 (Flyway)

| 설정 | 값 | 위치 |
|---|---|---|
| 마이그레이션 위치 | `src/main/resources/db/migration/` (`V1`~`V33`) | — |
| `baseline-on-migrate` | `true` | `application.yml` |
| `baseline-version` | `0` | `application.yml` |
| JPA `ddl-auto` | `none` | `application-local.yml`, `application-test.yml` |

애플리케이션을 기동하면 Flyway가 마이그레이션을 적용합니다. 적용 이력은 `flyway_schema_history` 테이블에 남습니다. 작성 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md#db-마이그레이션-flyway)를 참고하세요.

| 버전 | 내용 |
|---|---|
| `V1`, `V2` | `vector`, `postgis` extension 생성 |
| `V3` | `faq_category`, `faq`(1024차원 벡터, HNSW cosine 인덱스), `faq_old`, `question_log`, `faq_log` |
| `V4` | `stores`(PostGIS `geography` 자동 생성 컬럼, GIST 인덱스), `service_types`, `store_services` |
| `V5` | 임시 매장·서비스 유형 데이터 |
| `V6` | DB 타임존 `Asia/Seoul` |
| `V7` | `faq_old` → `old_faq`, 작성자 컬럼명 `created_by`/`updated_by` |
| `V8` | `faq_log.rank`를 INTEGER로, `faq_log`·`faq_category`에 `created_at` |
| `V9` | `users`(활성 사용자 이메일 부분 UNIQUE), 기존 테이블의 사용자 FK |
| `V10` | `refresh_tokens`, `faq_category.updated_at` |
| `V11` | `faq_category`, `service_types` soft delete와 활성 행 기준 UNIQUE |
| `V12` | `question_log.llm_question`(생성 답변), `answer_attempts_history` |
| `V13` | `faq`, `old_faq`에 `intent` (`GENERAL`/`STORE_DATA`/`USER_DATA`, 기본 `GENERAL`) |
| `V14` | `forbidden_words` (`ACTIVE`/`INACTIVE`) |
| `V15` | `unanswered_question_groups`(대표 질문, 중심 벡터, 질문 수, 처리 상태 `PENDING`/`APPROVED`/`ON_HOLD`/`REJECTED`), `unanswered_questions`(질문 벡터, 원인 `NO_FAQ`/`INSUFFICIENT_FAQ`, 가장 가까운 FAQ) |
| `V23` | `embedding_profiles`(엔진·모델 이름·차원·버전의 조합), `faq_embeddings`(Profile별 FAQ 벡터, HNSW cosine 인덱스), `unanswered_question_embeddings`, `unanswered_group_embeddings`(Profile별 미응답 질문 벡터와 묶음 중심 벡터) |
| `V25` | 예전 벡터 컬럼 삭제 (`faq.vector`, `old_faq.vector`, `unanswered_questions.question_vector`, `unanswered_question_groups.centroid`) |
| `V31` | `chat_jobs`(답변 작업: 답변 시도와 1:1, 상태 `QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`, 시작·종료 시각). 시도를 지우면 함께 지워짐 |
| `V32` | `faq`, `old_faq`에 `cacheable` 추가, `faq_question_variants` 생성 및 기존 질문 이관, `faq_embeddings`의 연결 키를 FAQ ID에서 variant ID로 변경 |
| `V33` | `chat_jobs`에 요청 정보(`request_context`)·성공 결과(`result_payload`) JSONB, `heartbeat_at`, `updated_at`, 복구용 인덱스와 처리 단계(`phase`)·단계 갱신 시각(`phase_updated_at`)·마감(`deadline_at`) 추가. 기존 작업의 단계·마감은 NULL 유지. 요청 좌표·지역은 작업 종료와 같은 트랜잭션에서 삭제 |

표에 없는 버전은 각 SQL 파일을 참고하세요. `V32`는 기존 FAQ 질문을 variant로 이관하지만, 기본 FAQ와 관리자 계정을 새로 생성하지는 않습니다. 로컬에서는 `tools/faq-db/reset-local-db.ps1`이 기본 계정과 시드 CSV의 FAQ·질문 표현을 넣습니다. 벡터는 별도로 백필합니다([안내](../how-to/reset-local-db.md)).

FAQ 벡터는 `faq_embeddings`에 variant ID와 Profile ID별로 저장합니다. `V15`까지 쓰던 엔티티의 벡터 컬럼은 `V25`에서 지웠습니다.

## Ollama 관련 설정 두 곳

Ollama 설정 키는 두 묶음이 있고, 서비스가 실제로 쓰는 것은 `ollama.*`입니다.

| 설정 키 | 읽는 주체 | 용도 |
|---|---|---|
| `ollama.*` | `EmbeddingConfig`, `LlmConfig` | `base-url`은 임베딩(`/api/embed`)과 답변 생성(`/api/chat`)이 함께 씁니다. `embedding.model`, `connect-timeout`, `read-timeout`은 임베딩용입니다 |
| `spring.ai.ollama.*` | Spring AI 자동 구성 | `local` 프로필에만 있습니다. 서비스는 자동 구성이 만든 빈을 호출하지 않습니다. `LlmConfig`는 이 중 `chat.options.model`만 읽고, 값이 있으면 `OLLAMA_CHAT_MODEL` 대신 모델 이름으로 씁니다 |

왜 Spring AI 자동 구성과 따로 두는지는 [architecture.md](../architecture.md#spring-ai를-쓰는-범위)를 참고하세요.

`application-prod.yml`에는 `ollama.*`만 있습니다. 답변 생성도 `ollama.base-url`을 읽으므로 `prod`에서도 `OLLAMA_BASE_URL`이 그대로 적용됩니다.

## Spring AI 현재 상태

| 설정 | `local` | `prod` | `test` |
|---|---|---|---|
| `spring.ai.model.chat` | `ollama` | `none` | `none` |
| `spring.ai.model.embedding` | `none` | `none` | `none` |
| `spring.ai.vectorstore.type` | `none` | `none` | 지정하지 않음 (pgvector 사용) |
| pgvector 차원 / 인덱스 / 거리 | 주석 처리됨 | 없음 | `1024` / `HNSW` / `COSINE_DISTANCE` |
| pgvector `initialize-schema` | 주석 처리됨 | 없음 | `true` |

- `spring.ai.model.chat`은 Spring AI가 자동으로 만드는 채팅 모델 빈을 정합니다. 서비스가 실제로 호출하는 클라이언트는 이 값과 관계없이 `LlmConfig`가 `AI_MODE`에 따라 만듭니다.
- `local`과 `prod`에서는 Spring AI의 `PgVectorStore`를 쓰지 않습니다. 임베딩과 검색은 `EmbeddingService`와 `FaqVectorRepository`가 직접 처리합니다.
- `test`에서는 테스트 전용 임베딩 구현을 등록해 `PgVectorStore`가 만들어지고, 그 스키마(1024·HNSW·cosine)와 저장·검색을 검증합니다.
- 따라서 **pgvector 관련 설정값을 검증하는 것은 테스트 프로필뿐**입니다. Spring AI 벡터 스토어를 정식 채택하면 `local`의 주석을 되살리고 두 프로필을 맞춰야 합니다.

JPA의 `ddl-auto: none`은 JPA 테이블 자동 생성을 끄는 설정입니다. 별도의 Spring AI pgvector `initialize-schema: true`까지 끄는 것은 아닙니다.

## 기타 고정 설정값

| 설정 | 값 | 파일 |
|---|---|---|
| Actuator 노출 endpoint | `health`만 | `application.yml` |
| Health 상세 표시 | `local`은 `always`, `prod`는 `never` | `application-local.yml`, `application-prod.yml` |
| 인증 없이 호출 가능한 경로, `ADMIN` 경로 | [architecture.md#인증](../architecture.md#인증) | `SecurityConfig` |
| 채팅 질문 최대 길이 | 4000자 | `ChatRequestDto`, `ChatService` |
| 답변 작업 스트림 | 키 `chat:jobs`, 그룹 `chat-workers`, 수동 ACK. 발행 표시 `chat:jobs:published:{jobId}`, 결과 키 `chat:jobs:result:{jobId}` | `ChatJobStream`, `ChatJobResultStore` |
| 다시 발행 | 정리 한 번에 대기 작업 최대 1,000건의 발행 표시를 확인하고, 최대 100건을 다시 발행 | `ChatJobWorker` |
| 넘겨받기 | 정리 한 번에 방치된 메시지 최대 100건을 확인하고, 그중 빈 슬롯 수만큼 넘겨받음. 메시지를 잃은 작업은 한 번에 최대 100건을 실패 처리 | `ChatJobWorker` |
| 스트림 정리 | 정리마다 모든 메시지가 ACK된 앞부분을 `XTRIM MINID`로 지움 | `ChatJobStream` |
| 답변 Worker 종료 대기 | 실행 중인 작업을 20초 기다린 뒤 끝나지 않은 작업은 대기 상태로 돌려놓고 메시지를 다시 발행함. 배포 Compose의 `stop_grace_period`는 35초 | `ChatJobWorker`, `docker-compose.deploy.yml` |
| 정리 작업 한 번에 처리하는 작업 수 | 대기 초과 최대 100건 | `ChatJobWorker` |
| Ollama 임베딩 요청 옵션 | `num_ctx: 4096`, `keep_alive: 30m` | `OllamaEmbeddingClient` |
| 임베딩 차원 | 1024 (DB 컬럼 `vector(1024)`와 같음). 다른 차원의 응답은 거절 | `EmbeddingClient` |
| Nginx 프록시 응답 제한 시간 | `180s`. 임베딩 백필 경로만 `3600s` | `infra/nginx/nginx.conf` |
| Nginx `/chat/jobs/`·`/api/chat/jobs/` (상태 조회) | 게스트 요청 속도 제한을 적용하지 않음 | `infra/nginx/nginx.conf` |
| Nginx `/chat/`·`/api/chat/` 게스트 요청 속도 제한 (IP별, `Authorization: Bearer …`가 없는 요청) | `CHAT_GUEST_RATE_LIMIT_RATE`(기본 `30r/m`), `CHAT_GUEST_RATE_LIMIT_BURST`(기본 `10`) | `.env`, `docker-compose.deploy.yml`, `infra/nginx/nginx.conf` |
| 테스트 타임존 | `Asia/Seoul` | `build.gradle` |
| 테스트 JVM 최대 힙 | `1024m` | `build.gradle` |
| DB 타임존 | `V6__set_database_timezone.sql` | 마이그레이션 |


## 자동 테스트 구성

- 이미지: `infra/postgres/Dockerfile`(PostgreSQL 18 + pgvector 0.8.6 + PostGIS 3.6.4). Compose와 Testcontainers가 같은 Dockerfile을 사용합니다. `build.gradle`이 `infra/postgres`를 테스트 리소스로 등록합니다.
- 테스트 DB: `ubot_test`. 사용자 `ubot_test`, 비밀번호는 실행마다 임의 생성. 호스트 포트는 Testcontainers가 할당하고 `@ServiceConnection`으로 Spring에 연결합니다.
- 개발 DB와 볼륨을 공유하지 않고 컨테이너를 재사용하지 않습니다. 테스트 클래스가 끝나면 컨텍스트와 컨테이너를 정리합니다.
- 사전 요구사항은 JDK 17 이상과 실행 중인 Docker입니다. Java 21 toolchain은 없으면 Gradle이 자동으로 내려받습니다. `.env`와 개발 Compose는 필요하지 않습니다.
- `application-test.yml`은 JWT 비밀 키, `kakao.local.api-key: test-key`, 임베딩 설정(`ollama.base-url: http://localhost:11434` 등)을 테스트 전용 값으로 고정합니다. `AI_MODE`는 지정하지 않으므로 기본값 `ollama`로 동작합니다.
- 테스트 JVM의 최대 힙은 `1024m`입니다. Gradle 기본값(512MB)으로는 전체 테스트를 한 번에 돌릴 때 `Java heap space`로 실패했습니다.
- `tools/ci.ps1`은 CI와 같은 명령(`gradlew clean build --no-daemon --console=plain`)을 로컬에서 실행합니다.
- 예외적으로 `IntentClassificationAnalysis`는 `@TestPropertySource`로 Ollama 주소를 `http://localhost:11435`로 바꿔 실제 Ollama를 호출합니다. 환경변수 `RUN_INTENT_ANALYSIS=true`가 있을 때만 실행되고, 평소의 `gradlew test`와 CI에서는 건너뜁니다([실행 방법](../troubleshooting.md#intentclassificationanalysis가-실행되지-않음)).

## LLM 호출 모듈 설정

`LlmConfig`는 답변 생성 엔진에 따라 `LlmClient` 구현체 하나만 등록합니다. 엔진은 `AI_MODE`가 `ollama`·`vllm`이면 그 값이고, `custom`이면 `AI_CHAT_ENGINE`입니다. 클라이언트는 `LlmClientFactory`가 만들고, 두 엔진 모두 같은 `LLM_CONNECT_TIMEOUT`, `LLM_READ_TIMEOUT`을 씁니다.

| 엔진 | 구현체 | 주소 | 모델 이름 |
|---|---|---|---|
| `ollama` | `OllamaClient` (Spring AI `OllamaChatModel`) | `ollama.base-url` (`OLLAMA_BASE_URL`) | `spring.ai.ollama.chat.options.model`이 있으면 그 값, 없으면 `OLLAMA_CHAT_MODEL` |
| `vllm` | `OpenAiCompatibleLlmClient` | `LLM_BASE_URL` | `LLM_MODEL` |

- 모듈을 만들 때는 외부 서버에 접속하지 않습니다. 모델을 자동으로 내려받거나 요청을 자동으로 재시도하지도 않습니다.
- Ollama에서 모델 이름이 비어 있으면 기동은 되고, 실제 호출 때 `LLM_MODEL_NOT_CONFIGURED`(`LLM-002`) 오류를 반환합니다. 사용할 모델을 Ollama에 미리 준비하고 모델 이름을 설정하세요.
- 답변 시도 기록(`answer_attempts_history.llm_model`)에는 선택된 엔진의 모델 이름이 남습니다. `ollama`면 `OLLAMA_CHAT_MODEL`, `vllm`이면 `LLM_MODEL` 값입니다.

자세한 내용은 [LLM 모듈 안내](../how-to/llm-module.md#ai-실행-엔진-선택)에 있습니다.

## 입력·출력 AI 가드 모델 설정

가드의 API 모델명은 `AiGuardModelService`에서 실행 환경에 맞게 결정합니다. 설정하지 않은 엔진은
`AI_MODE`의 답변 생성 엔진을 따릅니다(`custom`이면 `AI_CHAT_ENGINE`). Ollama는 `OLLAMA_CHAT_MODEL`,
vLLM은 `LLM_MODEL`을 기본 모델명으로 사용합니다. Hugging Face 체크포인트 이름을 요청 모델명으로 고정하지 않습니다.
vLLM의 `LLM_MODEL`은 서버의 `LLM_SERVED_MODEL_NAME`과 같아야 합니다. 실험에서 선택한 체크포인트와 프롬프트 기록은
`src/main/resources/prompts/ai-guard/provenance.json`에 그대로 보존합니다.

| 환경변수 | 생략하거나 비웠을 때 | 별도 지정 시 |
|---|---|---|
| `AI_GUARD_ENGINE` | 답변 생성 엔진 | `ollama` 또는 `vllm` |
| `AI_GUARD_BASE_URL` | 선택한 엔진의 기존 주소. Ollama는 `ollama.base-url`/`OLLAMA_BASE_URL`, vLLM은 `LLM_BASE_URL` | 해당 엔진의 가드 전용 주소. vLLM은 `/v1`까지 포함 |
| `AI_GUARD_MODEL` | Ollama는 `spring.ai.ollama.chat.options.model`/`OLLAMA_CHAT_MODEL`, vLLM은 `LLM_MODEL`(기본 `ubot-chat`) | Ollama 등록 모델명 또는 vLLM의 served model name |
| `AI_GUARD_CONNECT_TIMEOUT` | `LLM_CONNECT_TIMEOUT`(기본 `3s`) | 가드 연결 제한 시간 |
| `AI_GUARD_READ_TIMEOUT` | `LLM_READ_TIMEOUT`(기본 `120s`) | 가드 응답 제한 시간 |
| `AI_GUARD_MAX_CONCURRENT` | `LLM_MAX_CONCURRENT`(기본 `4`) | 별도 가드 서버의 호출 허가 수. 같은 서버에서는 기존 답변 생성 상한을 공유하며 이 값으로 늘리지 않음 |
| `AI_GUARD_PERMIT_WAIT_TIMEOUT` | `5s` | 가드 호출 허가 대기 시간 |

가드와 답변 생성이 같은 서버를 사용하면 기존 `LlmService`의 동시 호출 허가·취소·지표 처리를 공유합니다.
별도 연결 설정이 없으면 전송 클라이언트도 그대로 재사용합니다. 같은 서버에 다른 모델명이나 제한 시간을 지정해도
공유한 허가를 얻은 뒤 해당 설정으로 호출합니다. 취소·시간 초과 후에도 실제 호출이 계속 실행 중이면 허가를 먼저 돌려주지 않습니다.
가드 대기 한도를 넘으면 `LLM_BUSY`를 반환하며, 이를 안전 판정이나 자동 재시도로 바꾸지 않습니다.

서버는 URL의 프로토콜·호스트·포트로 구분합니다. 호스트 대소문자, 기본 포트 표기, 끝의 `/`나 API 경로 차이로
같은 서버의 허가를 분리하지 않으며, `localhost`·`127.0.0.1`·`[::1]`도 같은 로컬 서버로 봅니다.
다른 DNS 이름이나 프록시 뒤의 실제 서버가 같은지는 조회하지 않습니다. 같은 모델 서버에는 같은 주소를 사용하세요.
별도 서버일 때만 가드 전용 허가를 사용하며, 답변 생성의 기존 호출 상한은 변경하지 않습니다.

입력·출력 가드의 `forbidden_words`에는 해당 요청의 질문·답변·대화·근거·조회 자료에서 매칭된 활성 금칙어만 넣습니다.
기존 코드 필터의 공백·숨은 문자·전각 문자·대소문자와 단어 경계 규칙을 재사용하며, JSON 필드명이나 서로 다른 필드를
이어 붙인 문자열은 매칭 대상으로 만들지 않습니다. 캐시를 읽기만 하므로 추가 DB 조회나 등록 변경은 하지 않습니다.
자료에 없는 금칙어가 늘어도 전달 목록은 늘지 않으며, 매칭된 단어를 개수 순으로 임의 누락시키지 않습니다.
금칙어 매칭은 프롬프트 자료 선정이며, 인용·피해 신고 등의 안전 판정 기준이나 판정 프롬프트 원본은 변경하지 않습니다.

`AI_INPUT_GUARD_ENABLED`, `AI_OUTPUT_GUARD_ENABLED`, `AI_INPUT_LLM_GUARD_ENABLED`,
`AI_OUTPUT_LLM_GUARD_ENABLED`는 모두 기본 `true`입니다. 로컬에서 AI 가드 없이 개발하려면 두 `*_LLM_GUARD_ENABLED`만
`false`로 지정할 수 있습니다. 코드 가드는 유지하며, 답변 생성·기존 질문 분류의 모델 설정은 여전히 필요합니다.
공식 FAQ 캐시 적중은 출력 코드 검사만 거치고, 새로 생성한 답변은 활성화된 출력 LLM 검사를 거칩니다.

로컬 Ollama를 쓸 때는 `.env.example`의 `OLLAMA_CHAT_MODEL`과 로컬에 준비한 모델명을 맞추세요.
로컬의 답변 생성은 Ollama로 두고 가드만 별도 vLLM에 보내려면 다음처럼 세 값을 명시합니다.

```properties
AI_GUARD_ENGINE=vllm
AI_GUARD_BASE_URL=http://guard-host:8000/v1
AI_GUARD_MODEL=ubot-guard
```

`ubot-guard`는 예시이며 실제 가드 서버에 등록한 이름으로 바꿔야 합니다. 반대로 가드만 Ollama를 쓰려면
`AI_GUARD_ENGINE=ollama`와 Ollama 주소·등록 모델명을 지정합니다. 이 설정은 답변 생성·임베딩 설정을 바꾸지 않으며,
모델을 자동 다운로드하거나 서버를 실행하지 않습니다. 지정한 주소에 해당 모델이 준비되어 있어야 합니다.

## 관리자 위험 표현 분석 전용 모델 설정

관리자 `ReviewWorker` 분석은 `Qwen/Qwen3-14B-AWQ`를 사용합니다. 공통 상담·실시간 가드의
`LLM_BASE_URL`과 `LLM_MODEL`은 바꾸지 않습니다. 기존 채팅 우선 입장 제어와 오류 처리를 재사용합니다.
설정이 없어도 애플리케이션은 기동하며, 분석 시에는 다른 모델로 대체하지 않고 실패합니다.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `GUARD_REVIEW_LLM_BASE_URL` | 빈 값 | 전용 OpenAI 호환 API. `/v1` 포함 |
| `GUARD_REVIEW_LLM_CONNECT_TIMEOUT` | `3s` | 모델 연결 제한 |
| `GUARD_REVIEW_LLM_READ_TIMEOUT` | `180s` | 추론 응답 대기 제한 |
| `GUARD_REVIEW_LLM_MAX_TOKENS` | `16384` | 추론을 포함한 출력 상한 |
| `GUARD_REVIEW_RUNTIME_BASE_URL` | 빈 값 | 전용 실행 제어 API. `/v1` 제외 |
| `GUARD_REVIEW_RUNTIME_TOKEN` | 빈 값 | 제어기와 공유하는 비밀 토큰. Git에 실제 값 저장 금지 |
| `GUARD_REVIEW_RUNTIME_CONNECT_TIMEOUT` | `3s` | 제어기 연결 제한 |
| `GUARD_REVIEW_RUNTIME_READ_TIMEOUT` | `300s` | 초기 모델 로딩을 포함한 제어 응답 대기 |

각 값은 `app.guard-review.llm.*` / `app.guard-review.runtime.*` Spring 속성으로도 지정할 수 있습니다.
관리자 모델에는 Thinking을 활성화하고 `temperature=0.6`, `top_p=0.95`, `top_k=20`, `min_p=0`을 사용합니다.
모델은 사용권을 얻을 때만 준비하며 마지막 사용권 반납 시 전용 컨테이너를 정지합니다.
모델 파일은 디스크에 남습니다. 오차단 재검사는 LLM을 로드하지 않습니다.

기존 운영 Compose나 실제 서버 설정에 자동 연결되지 않습니다. 별도 제어기 배치·사설 통신 경로·자원 확보가 필요합니다.
배치 예시와 장애·동시 사용 시 동작은 [관리자 모델 실행 안내](../how-to/admin-risk-runtime.md)를 참고하세요.

## 임베딩 호출 모듈 설정

`EmbeddingConfig`는 임베딩 엔진에 따라 `EmbeddingClient` 구현체 하나만 등록합니다. 엔진은 `AI_MODE`가 `ollama`·`vllm`이면 그 값이고, `custom`이면 `AI_EMBEDDING_ENGINE`입니다. 클라이언트는 `EmbeddingClientFactory`가 만듭니다.

| 엔진 | 구현체 | 호출 | 모델 이름 | 제한 시간 |
|---|---|---|---|---|
| `ollama` | `OllamaEmbeddingClient` | `ollama.base-url`(`OLLAMA_BASE_URL`)의 `/api/embed` | `OLLAMA_EMBEDDING_MODEL` | `OLLAMA_CONNECT_TIMEOUT`, `OLLAMA_READ_TIMEOUT` |
| `vllm` | `OpenAiCompatibleEmbeddingClient` | `EMBEDDING_BASE_URL`의 `/embeddings` | `EMBEDDING_MODEL` | `EMBEDDING_CONNECT_TIMEOUT`, `EMBEDDING_READ_TIMEOUT` |

- 모듈을 만들 때는 외부 서버에 접속하지 않습니다.
- 두 구현체 모두 응답이 1024차원이 아니거나 유한하지 않은 값이 있으면 `EM-002`로 거절합니다.
- 답변 시도 기록(`answer_attempts_history.embedding_model`)에는 선택된 엔진의 모델 이름이 남습니다.

벡터를 저장하거나 검색할 때는 `EmbeddingProfileService`가 지금 설정에 해당하는 Embedding Profile을 찾아 그 Profile의 벡터만 다룹니다. Profile은 아래 네 값의 조합이고, 없으면 처음 쓸 때 `embedding_profiles`에 만들어집니다. 설정은 실행 중에 바뀌지 않으므로 백엔드는 Profile을 한 번 조회한 뒤 기억합니다.

| 값 | 출처 |
|---|---|
| `provider` | 임베딩 엔진이 `ollama`면 `ollama`, `vllm`이면 `openai-compatible` |
| `model_name` | 위 표의 모델 이름 |
| `dimensions` | `1024` (고정) |
| `profile_version` | `EMBEDDING_PROFILE_VERSION` |

이 중 하나라도 바뀌면 다른 Profile이 되어 기존 벡터가 검색에 쓰이지 않습니다. 그때는 백필로 새 Profile의 벡터를 채웁니다. 절차는 [LLM 모듈 안내](../how-to/llm-module.md#백필)에 있습니다.

현재 구현은 채팅에서 검색한 FAQ와 원래 질문을 `AiService` → `PromptService` → `LlmService`로
전달하고, 완성된 답변을 한 번에 반환합니다. 기본 프롬프트 파일인 `prompts/faq-system.txt`,
`prompts/faq-user.txt`에는 본문이 들어 있습니다. 파일이 없거나 비어 있거나 자리표시자가 빠지면
모델을 호출하지 않고 `CHAT-014`로 실패합니다. 외부 프롬프트 파일을 사용하려면
`prompt.faq.system-location`, `prompt.faq.user-location`에 Spring `file:` 리소스 경로를 지정합니다.
호출 계약과 테스트 방법은 [LLM 모듈 연결 안내](../how-to/llm-module.md)를 참고하세요.
