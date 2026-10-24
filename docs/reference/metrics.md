# 운영 지표 Reference

백엔드가 `/actuator/prometheus`로 내보내는 직접 만든 지표, Prometheus exporter가 모으는 지표, 관리자 운영 개요 API(`GET /admin/operation-monitoring/services/overview`)가 돌려주는 값을 정리합니다. 이름은 Prometheus에서 조회하는 이름입니다. 코드에서는 점(`.`)으로 구분한 이름(`chat.worker.completed`)을 쓰고, timer에는 `_seconds`, counter에는 `_total`이 붙습니다.

## 공통 규칙

- 모든 지표에 프로세스 역할 태그 `role`(`api`·`worker`·`all`)이 붙습니다(`management.metrics.tags.role`).
- 백분위를 계산하는 지표는 `application.yml`의 `management.metrics.distribution.slo`에 정한 구간만 histogram 버킷(`_bucket`)으로 내보냅니다. 태그 조합마다 버킷 수만큼 시계열이 생기므로 구간을 늘릴 때 주의합니다.
- 태그에는 값의 종류가 정해진 것만 넣습니다. 사용자·작업·FAQ ID, 질문 원문은 태그로 쓰지 않습니다.
- counter는 처음 일이 생길 때 만들어집니다. 한 번도 생기지 않은 counter는 Prometheus에 시계열이 없으므로 비율을 계산할 때 `or vector(0)`으로 0을 채웁니다.

## Chat Job

`chat_jobs_submitted_total`은 `backend`, 나머지는 `ai-worker`에서 나옵니다.

| 지표 | 종류 | 태그 | 의미 |
|---|---|---|---|
| `chat_jobs_queued` | gauge | – | 대기 중 작업 수. 모든 Worker가 같은 DB를 보므로 `max`로 집계 |
| `chat_jobs_oldest_queued_seconds` | gauge | – | 가장 오래 기다린 작업의 대기 시간 |
| `chat_jobs_submitted_total` | counter | `type`=`question`·`retry`·`research`, `user`=`member`·`guest` | 커밋된 접수 수. 시도를 만들다 실패한 요청은 세지 않음 |
| `chat_jobs_queue_wait_seconds` | histogram | – | 접수부터 Worker가 선점할 때까지 걸린 시간 |
| `chat_worker_execution_seconds` | histogram | – | 선점부터 결과 기록까지 걸린 실행 시간(1초~180초 구간) |
| `chat_worker_completed_total` | counter | `status`=`succeeded`·`failed`, `code`=`none`·`CHAT-012`… | 실행을 마친 작업. `code`는 실패 코드이고 성공은 `none` |
| `chat_worker_queue_expired_total` | counter | – | 대기 한도를 넘겨 실패로 끝낸 작업 |
| `chat_worker_terminated_total` | counter | `reason`=`exhausted`·`orphaned` | 실행 밖에서 실패로 끝낸 작업. 전달 횟수 소진(CHAT-015), 메시지 없이 오래 남은 실행 |
| `chat_worker_reclaimed_total` | counter | `reason`=`heartbeat`·`idle` | 다른 Worker의 작업을 넘겨받아 다시 실행한 횟수. heartbeat 끊김, ACK 지연(`CHAT_WORKER_RECLAIM_IDLE`) |
| `chat_worker_lease_lost_total` | counter | – | heartbeat를 갱신하려는데 이미 다른 실행이 넘겨받아 실행을 멈춘 횟수 |
| `chat_worker_heartbeat_failures_total` | counter | – | heartbeat 갱신 중 오류(DB 장애 등) |
| `chat_worker_active`, `chat_worker_ready`, `chat_worker_timeouts_total`, `chat_worker_discarded_total` | – | – | [Worker 장애 복구와 검증](../how-to/worker-reliability.md#배포와-관측) 참고 |

## LLM

LLM 호출은 대부분 `ai-worker`에서 나오지만, 관리자 기능(미응답 묶음 이름 등)이 쓰는 `backend`의 호출도 같은 지표에 들어갑니다.

| 지표 | 종류 | 태그 | 의미 |
|---|---|---|---|
| `llm_requests_seconds` | histogram | `client`=`chat`·`guard`, `kind`=`answer`·`judgment`, `outcome`=`success`·`timeout`·`unavailable`·`invalid_response`·`cancelled`·`error` | 모델 서버 호출 시간. 동시 호출 차례를 받은 뒤부터 잼. 요청 형식 오류(LLM-001)는 호출 전이라 세지 않음 |
| `llm_permit_wait_seconds` | histogram | `client`=`chat`·`guard` | 동시 호출 차례를 기다린 시간. 바로 받으면 0. 대기 중 시간 초과·취소도 포함 |
| `llm_permit_rejected_total` | counter | `client`=`chat`·`guard` | 대기 한도(FAQ AI 검토 등) 안에 차례를 얻지 못해 `LLM_BUSY`로 거절한 호출 |
| `llm_concurrency_limit` | gauge | `client`=`chat`·`guard` | 해당 서버의 동시 호출 상한 |
| `llm_concurrency_in_use` | gauge | `client`=`chat`·`guard` | 해당 서버에서 지금 모델을 호출 중인 수 |
| `llm_concurrency_waiting` | gauge | `client`=`chat`·`guard` | 해당 서버에서 차례를 기다리는 호출 수. 계속 0보다 크면 모델 서버 앞에서 줄을 서고 있음 |
| `ai_output_guard_total` | counter | `stage`=`body`·`evidence`, `outcome`=`pass`·`filtered`·`rejected`·`warning`, `rule` | 출력 가드 검사 결과. `rule`은 거절한 규칙 이름(`no_safe_content` 등)이고 그 밖에는 `none` |

요청·허가 대기 지표의 `client=chat`은 기존 답변 생성·질문 분류, `client=guard`는 입력·출력 AI 가드입니다.
동시성 게이지의 `client`는 허가를 소유한 서버를 나타냅니다. 같은 서버를 공유하면 `client=chat` 한 게이지가
답변 생성·분류·가드의 실제 합산 점유를 표시하고, 별도 서버일 때만 `client=guard` 게이지를 등록합니다.
두 서버 모두 같은 `client` 태그 키를 써 Prometheus 등록 충돌을 피합니다. 모델명·URL 등 가변값은 태그에 넣지 않습니다.
요청 지표의 성공은 모델 통신 성공이며 ALLOW 판정과 같지 않습니다. 호출 뒤 판정 JSON 파서·안전 정책의 거절은 판정 로그로 확인합니다.

```promql
# 입력·출력 AI 가드 모델 호출 p95와 통신 실패율(호출 허가 거절은 별도 counter)
histogram_quantile(0.95, sum(rate(llm_requests_seconds_bucket{client="guard"}[1h])) by (le))
(sum(rate(llm_requests_seconds_count{client="guard",outcome!~"success|cancelled"}[1h])) or vector(0))
  / sum(rate(llm_requests_seconds_count{client="guard"}[1h]))
```

## Retrieval

| 지표 | 종류 | 태그 | 의미 |
|---|---|---|---|
| `embedding_requests_seconds` | histogram | `operation`=`single`·`batch`, `outcome`=`success`·`timeout`·`unavailable`·`invalid_response`·`error` | 임베딩 서버 호출 시간. 채팅 검색·백필·상담 임베딩이 모두 포함됨 |
| `faq_vector_search_seconds` | histogram | `scope`=`all`·`intent` | FAQ 벡터 DB 검색 시간. 질문 임베딩 시간은 빠짐 |
| `faq_retrieval_top_similarity` | histogram | `scope`=`all`·`intent` | 벡터 검색 1위 후보의 코사인 유사도. 후보가 없으면 기록하지 않음 |

NO_FAQ·FAQ 부족은 따로 세지 않고 작업 결과 코드로 봅니다. `chat_worker_completed_total{code="CHAT-012"}`(검색 결과 없음), `{code="CHAT-013"}`(정확한 답변 없음)입니다.

## Embedding lifecycle

적체 수는 `backend`가 `METRICS_BACKLOG_REFRESH`(기본 5분)마다 DB에서 다시 센 값입니다. 스크레이프마다 DB를 조회하지 않고, 처음 세기 전에는 NaN입니다.

| 지표 | 종류 | 태그 | 의미 |
|---|---|---|---|
| `faq_embeddings_missing` | gauge | – | 삭제되지 않은 FAQ의 variant 중 현재 Embedding Profile의 `QUESTION` 벡터가 없는 수 |
| `faq_embeddings_stale` | gauge | – | 현재 Profile 벡터는 있지만 FAQ를 고친 뒤 다시 임베딩되지 않은(`faq_version`이 FAQ `version`과 다른) 수 |
| `outbox_events_pending` | gauge | – | 아직 처리할 미응답 질문 이벤트 수 |
| `outbox_events_retrying` | gauge | – | 그중 한 번 이상 실패해 재시도를 기다리는 수 |
| `outbox_events_dead` | gauge | – | 재시도 횟수(5번)를 다 써 더는 처리하지 않는 이벤트 수 |
| `counsel_embedding_jobs_total` | counter | `status`=`succeeded`·`failed`·`expired`, `code` | 상담 임베딩 작업 결과. `code`는 `EMBEDDING_TIMEOUT`·`EMBEDDING_UNAVAILABLE`·`WORKER_MODEL_MISMATCH`·`WORKER_INVALID_RESPONSE` 또는 `none` |

백필이 처리할 수는 `faq_embeddings_missing + faq_embeddings_stale`입니다([백필](../how-to/llm-module.md#백필)).

## DB

| 지표 | 출처 | 의미 |
|---|---|---|
| `hikaricp_connections_active`·`_idle`·`_pending`·`_max` | Spring 기본 | 커넥션 풀 상태 |
| `hikaricp_connections_acquire_seconds` | Spring 기본 | 커넥션을 얻는 데 걸린 시간(평균·최대) |
| `hikaricp_connections_usage_seconds` | Spring 기본, 구간 추가 | 커넥션을 빌려 쓴 시간. 느린 쿼리·긴 트랜잭션의 대리 지표 |
| `pg_stat_activity_max_tx_duration` | postgres-exporter | 상태별로 가장 오래 열린 트랜잭션 시간 |

쿼리별 실행 시간은 PostgreSQL `pg_stat_statements`가 필요합니다. `shared_preload_libraries` 설정과 DB 재시작이 필요해 아직 켜지 않았습니다.

## Redis

redis-exporter가 `--check-single-streams=chat:jobs`로 답변 작업 스트림을 조회합니다. 다른 Compose 파일로 redis-exporter를 띄운다면 같은 옵션을 넣어야 합니다.

| 지표 | 의미 |
|---|---|
| `redis_memory_used_bytes`, `redis_memory_max_bytes` | 사용 메모리, 상한. 상한이 0이면 `maxmemory`를 정하지 않은 것 |
| `redis_stream_length{stream="chat:jobs"}` | 스트림에 남은 메시지 수(ACK된 메시지는 정리 단계에서 지움) |
| `redis_stream_group_messages_pending{stream="chat:jobs"}` | 처리 대기 목록(PEL). Worker가 받았지만 아직 ACK하지 않은 메시지 수 |
| `redis_stream_group_lag{stream="chat:jobs"}` | 그룹이 아직 읽지 않은 메시지 수. Redis 7 이상에서만 나옴 |

## 자주 쓰는 PromQL

조회 기간은 예시로 `1h`를 씁니다.

```promql
# 답변 실행 p95(초)
histogram_quantile(0.95, sum(rate(chat_worker_execution_seconds_bucket[1h])) by (le))

# 재시도 비율
(sum(increase(chat_jobs_submitted_total{type="retry"}[1h])) or vector(0))
  / sum(increase(chat_jobs_submitted_total{type="question"}[1h]))

# NO_FAQ 비율
(sum(increase(chat_worker_completed_total{code="CHAT-012"}[1h])) or vector(0))
  / sum(increase(chat_worker_completed_total[1h]))

# LLM 시간 초과 비율
(sum(increase(llm_requests_seconds_count{outcome="timeout"}[1h])) or vector(0))
  / sum(increase(llm_requests_seconds_count[1h]))

# Worker의 LLM 동시 호출 포화도
sum(llm_concurrency_in_use{role!="api"}) / sum(llm_concurrency_limit{role!="api"})

# 출력 가드 거절률
(sum(increase(ai_output_guard_total{stage="body",outcome="rejected"}[1h])) or vector(0))
  / sum(increase(ai_output_guard_total{stage="body"}[1h]))
```

## 관리자 운영 개요 API

`GET /admin/operation-monitoring/services/overview?range=15m|1h|6h|24h`(기본 `6h`)는 기존 `services`·`summary`·`series`·`worker`에 아래 묶음을 더해 돌려줍니다. 비율·백분위·횟수는 `range` 기간 기준이고(이름에 기간이 적힌 값과 `worker.failuresPerSecond`·`worker.meanExecutionSeconds`는 최근 5분), 데이터가 없거나 분모가 0이면 `null`입니다.

| 묶음 | 필드 |
|---|---|
| `worker` | 기존 필드 + `p95ExecutionSeconds`, `p95QueueWaitSeconds`, `retryRatio`, `terminalFailureRatio`, `failureRatioByCode`, `reclaimsByReason`, `leaseLosses`, `heartbeatFailures` |
| `llm` | `p95AnswerSeconds`, `p95JudgmentSeconds`, `timeoutRatio`, `errorRatio`, `concurrencyInUse`, `concurrencyLimit`, `concurrencyWaiting`, `p95PermitWaitSeconds`, `outputGuardRejectionRatio`, `outputGuardRejectionsByRule` |
| `retrieval` | `embeddingP95Seconds`, `embeddingErrorRatio`, `vectorSearchP95Seconds`, `noFaqRatio`, `insufficientFaqRatio`, `topSimilarityP10`, `topSimilarityP50`, `topSimilarityBuckets` |
| `embedding` | `faqMissing`, `faqStale`, `outboxPending`, `outboxRetrying`, `outboxDead`, `counselFailures` |
| `database` | `meanAcquireMillis`, `maxAcquireMillis`, `p95UsageMillis`, `longestTransactionSeconds` |
| `redis` | `usedMemoryBytes`, `maxMemoryBytes`, `streamLength`, `pendingMessages`, `consumerLag` |

- `terminalFailureRatio`는 답을 못 찾은 결과(CHAT-012·013)를 뺀 실패 비율이고, 대기 만료와 실행 밖에서 끝낸 작업도 포함합니다.
- `failureRatioByCode`는 전체 완료 중 실패 코드별 비율입니다.
- `topSimilarityBuckets`는 1위 유사도의 누적 건수입니다. 키는 구간 상한(`le`)입니다.
- LLM 동시 호출 값은 `api` 역할을 뺀 값입니다.
- 질의 40여 개를 동시에 보낸 뒤 조합합니다. 하나라도 실패하면 기존과 같이 Prometheus 장애 오류로 응답합니다.

## 장애 알림

Backend·Worker·PostgreSQL·Redis 장애, Chat Job 지연, LLM 시간 초과 비율은 Prometheus 알림 규칙으로 판단해 Discord·Slack으로 보냅니다([장애 알림](../how-to/alerting.md)).

## 성능 영향

- 요청 경로에서 하는 일은 메모리 안의 counter 증가와 시간 기록뿐입니다. LLM 호출(수 초), 임베딩(수십 ms), 벡터 검색(수 ms)에 비하면 무시할 수준입니다.
- histogram은 응답 시간이 아니라 메모리와 스크레이프 크기를 늘립니다. 구간을 고정하고 태그 조합을 적게 둡니다.
- DB에서 세는 적체 수는 주기적으로만 조회합니다.
