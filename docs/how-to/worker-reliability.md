# 답변 Worker 장애 복구와 검증

## 보존하는 계약

- 질문·재시도·의도 재검색은 현재 비동기 계약인 `202 + jobId`를 유지합니다. 최종 상태는 `GET /chat/jobs/{jobId}`로 조회합니다. 예전 develop의 동기 응답을 사용하는 프론트는 이 계약으로 전환해야 합니다.
- 금지어, 회원·게스트 소유권, 게스트 질문 한도, 사용자 재시도 횟수, 재검색 중복 제한은 유지합니다.
- 작업 GET의 인증 사용자 조회·비회원 대화 승계·DB 상태 조회는 요청별 예산(`CHAT_JOB_READ_BUDGET`, 기본 1.5초)을 공유합니다. DB 오류·대기 만료·조회 실행 용량 소진은 `503 / CHAT-025`로 응답합니다. 조회만 취소하며 접수된 답변 작업은 계속 처리합니다. 전역 Hikari/JDBC 대기와 다른 API의 실행 방식은 유지합니다. 취소에 즉시 반응하지 않는 JDBC 조회도 최대 8개(`CHAT_JOB_READ_MAX_CONCURRENCY`)로 제한하고, 추가 대기열을 만들지 않습니다.
- 접수부터의 전체 기한은 기본 10분, 한 실행의 제한은 기본 180초입니다. 각각 `CHAT_WORKER_MAX_QUEUE_WAIT`, `CHAT_WORKER_EXECUTION_TIMEOUT`으로 설정합니다. 실제 실행 마감은 두 기한 중 이른 시각이며 Worker가 넘겨받아도 접수 시각은 바뀌지 않습니다. 선점 SQL이 이 마감을 `deadline_at`에 저장하고 같은 값을 돌려주므로 Worker의 실행 제한과 상태 조회의 마감 시각은 항상 같습니다. 만료된 대기 작업은 다음 정리 주기에 실패 상태로 반영되며, 선점 SQL은 만료 후 실행을 막습니다.
- V33에서 처리 단계와 마감 시각을 DB에 저장해 접수·상태 조회 응답으로 전달합니다. 실행 제한은 선점 시각부터 적용하며 단계 전환·모델 재시도로 연장되지 않습니다. 장애로 실행을 넘겨받으면 접수 후 전체 기한 안에서 새 실행 제한을 부여합니다. 단계 갱신은 실행 토큰과 RUNNING 상태를 검사하고, 저장 실패만으로 답변을 실패시키지는 않습니다. 사용자 문구와 시간 표시 방법은 [프론트 연동](frontend-integration.md#처리-단계와-제한-시간)을 참고하세요.
- DB 성공 커밋에 질문 로그 ID·매장 지도·위치 요청 정보를 함께 포함합니다. Redis 캐시 저장 전이나 캐시 유실 후에도 같은 결과를 조회합니다.
- 접수 당시 좌표·지역도 DB에 저장합니다. 복구된 메시지는 같은 요청 정보를 사용합니다.
- 요청 정보(`request_context`)는 성공·실패 확정 시 같은 DB 트랜잭션에서 NULL로 지웁니다. 대기·실행·재선점·종료 중 대기열 반환 시에는 보존하며, 완료 트랜잭션이 롤백되면 삭제도 롤백됩니다. 기존에 완료된 행을 소급 정리하지는 않습니다. 결과 지도(`result_payload`)의 중심점·매장 좌표는 답변 재조회에 필요하므로 유지하고, Redis 메시지는 기존 ACK 후 스트림 정리 정책을 따릅니다.

## 실행과 복구

- 새 메시지가 없으면 실행 슬롯을 반환한 뒤 쉽니다. 동시 실행 수 1에서도 복구가 슬롯을 사용할 수 있습니다.
- 실행은 DB heartbeat를 `CHAT_WORKER_HEARTBEAT_TIMEOUT`(기본 1분)의 3분의 1, 최대 15초 간격으로 갱신합니다. ACK가 늦어도 heartbeat가 살아 있으면 작업을 빼앗거나 실패 처리하지 않습니다.
- Worker가 죽으면 heartbeat가 1분 넘게 끊긴 작업의 메시지를 넘겨받습니다. 넘겨받기 전에 메시지 내용으로 작업을 확인해, heartbeat가 살아 있는 실행의 메시지는 건드리지 않습니다(전달 횟수가 늘지 않음). 5분(`CHAT_WORKER_RECLAIM_IDLE`) 넘게 ACK되지 않은 메시지는 작업 상태를 보지 않고 넘겨받기를 시도하는 마지막 안전망입니다.
- 실행 제한을 넘기면 계산 Future를 취소하고 타임아웃을 기록한 뒤 Worker 슬롯을 돌려줍니다. 인터럽트를 무시한 외부 호출이 늦게 반환해도 그 결과는 저장하지 않습니다. 이미 모델 서버에 전달된 계산을 원격에서 강제 중단한다는 보장은 없으므로 HTTP 호출 제한도 유지합니다.
- Worker 안의 vLLM HTTP 연결 실패·5xx는 기본 5초(`CHAT_WORKER_MODEL_RETRY_BACKOFF`) 간격으로 같은 HTTP 요청을 다시 보냅니다. 같은 작업·사용자 시도·실행 토큰을 유지하고 완료 전 ACK하지 않습니다. 기다리는 동안 heartbeat를 유지하며 단계는 `WAITING_FOR_MODEL`입니다. 재시도는 마감을 따로 계산하지 않고, 마감이 지나면 Worker가 계산을 취소해 대기를 끝냅니다(`CHAT-016`). 이미 끝난 FAQ 검색·도구 실행은 반복하지 않습니다. 4xx·잘못된 응답·HTTP 타임아웃은 기존 오류 처리를 따르며, Worker 밖의 모델 호출에는 자동 재시도를 적용하지 않습니다.
- 끊긴 LLM 호출은 반환될 때까지 `LlmService`의 동시 호출 허가(`LLM_MAX_CONCURRENT`, 기본 Worker 동시 실행 수)를 쥐고 있습니다. 슬롯을 먼저 돌려줘도 모델 서버에 걸리는 호출은 상한을 넘지 않고, 새 작업은 허가를 기다립니다. 기다리는 중에 취소되면 모델을 부르지 않습니다.
- 대기 한도를 넘긴 작업은 정리 한 번에 100건씩, 남아 있으면 최대 1,000건까지 이어서 실패로 끝냅니다. 정리 한 번이 길어져 다른 정리 단계와 health 확인이 밀리지 않게 하려는 상한이며, 남은 작업은 다음 정리에서 끝냅니다.
- 도구 호출 응답을 계속 반복하는 OpenAI 호환 모델은 최대 8회의 도구 처리 후 잘못된 응답으로 종료합니다.
- 복구 발행은 Lua로 XADD와 발행 표시를 함께 기록합니다. 예약만 남는 구간을 없애고, 이전 버전의 `reserved` 표시도 회수합니다.
- 미발행 작업은 ID 기반 페이지를 넘겨 검사합니다. 앞 1,000개가 이미 발행됐다는 이유로 뒤의 누락 작업을 건너뛰지 않습니다.
- 발행 표시가 있어도 표시가 가리키는 ID에 같은 작업(`jobId`)의 메시지가 없으면 미발행으로 봅니다. 스트림을 지운 뒤 같은 ms에 들어온 메시지는 이전 ID를 다시 받을 수 있어서 `jobId`까지 비교합니다. 스트림만 사라진 뒤 새 질문이 스트림을 다시 만들어도 사라진 대기 작업을 다시 발행합니다. 재발행은 정리 한 번에 최대 1,000건입니다.
- 대기 시간이 지난 작업은 정리 주기 전이라도 선점 SQL에서 거절합니다.
- 실행 소유권은 `started_at`으로 검증합니다. 회수된 이전 실행의 완료·실패 기록·heartbeat는 거절합니다.

## 장애 테스트

| 상황 | 검증 위치 |
|---|---|
| DB가 멈춘 인증 조회·잠긴 작업 조회·잠긴 비회원 승계도 2초 안에 503, 복구 후 정상 조회 | `ChatJobDatabaseOutageIntegrationTest` |
| 모델 재시도 로그에 작업·시도 연결, 저장 실패·권한 상실 시 완료로 오인하지 않음, 원문 비노출 | `ChatJobWorkerLoggingTest` |
| 모델 5xx의 상태·재시도·복구 성공, 4xx 즉시 실패·마감 만료 로그, HTTP 오류 본문 비노출 | `LlmRecoveryLoggingTest` |
| 작업 조회 오류 WARN은 전역 핸들러에서 한 번 기록, 상세 원인·실행 수는 DEBUG, 응답 503 유지 | `ChatJobReadExecutorLoggingTest` |
| Worker 내부 DB·예상 밖 예외의 오류 코드·예외 종류 보존, SQL·본문 비노출 | `ChatJobProcessorLoggingTest` |
| 인증과 작업 조회가 예산 공유, 대기 취소·실행 상한·다른 API 동기 실행 유지 | `ChatJobReadExecutorTest` |
| DB 커밋 직후 Redis 결과가 아직 없음, 캐시 손상·삭제 | `ChatLlmIntegrationTest.committedSuccessIncludesMapBeforeRedisWriteAndAfterCacheLoss` |
| 성공·실행 실패·대기 실패·방치된 실행 종료 시 요청 정보 삭제, 결과 조회 유지 | `ChatLlmIntegrationTest.committedSuccessIncludesMapBeforeRedisWriteAndAfterCacheLoss`, `terminalFailuresClearRequestContext` |
| 완료 롤백·대기열 반환 시 요청 정보 보존 | `ChatLlmIntegrationTest.rolledBackCompletionPreservesContextForRecovery` |
| 같은 게스트 질문의 실행 시간이 겹침 | `ChatLlmIntegrationTest.sameGuestQuestionWithOverlappingExecutionAlwaysReturnsItsOwnResult` |
| Redis 유실 후 좌표·지역 복원 | `ChatLlmIntegrationTest.redisLossRecoveryPreservesCoordinatesAndRegionFromDatabase` |
| 스트림만 유실되고 발행 표시는 남은 채 새 질문이 스트림을 다시 만듦 | `ChatLlmIntegrationTest.queuedJobIsRecoveredWhenStreamIsLostButMarksRemain`, `ChatJobStreamIntegrationTest.markPointingToLostMessageIsTreatedAsUnpublished`, `ChatJobStreamIntegrationTest.reusedMessageIdOfAnotherJobIsTreatedAsUnpublished` |
| 외부 트랜잭션 롤백/커밋 전 발행 방지 | `ChatLlmIntegrationTest.submissionWaitsForOuterCommitAndRollbackPublishesNothing` |
| 게스트 질문 후 로그인, 다른 회원 접근 거절 | `ChatLlmIntegrationTest.guestCanContinuePollingAfterLoginAndOtherUsersStillCannotReadIt` |
| 후속 재시도가 있는데 이전 작업의 retryable 조회 | `ChatLlmIntegrationTest.oldFailedJobStopsOfferingRetryWhenNewAttemptExists` |
| 회수된 이전 실행의 늦은 성공·실패 | `ChatLlmIntegrationTest.staleOwnerCannotOverwriteOrFailTakenOverJob` |
| 모델이 인터럽트를 무시하고 늦게 반환 | `ChatJobWorkerTest.timeoutFreesSlotAndDiscardsLateAnswerEvenIfModelIgnoresInterrupt` |
| 끊긴 호출이 남아 있어도 모델 동시 호출 상한 유지, 기다리다 취소되면 호출 안 함 | `LlmServiceTest.limitsConcurrentModelCalls`, `cancelledWhileWaitingDoesNotCallModel` |
| heartbeat가 끊긴 실행은 1분 만에 넘겨받고, 살아 있는 실행의 메시지는 건드리지 않음 | `ChatJobWorkerTest.silentRunIsReclaimedBeforeReclaimIdle`, `liveRunMessageIsNotReclaimed` |
| 메시지 내용 확인은 전달 횟수를 늘리지 않음 | `ChatJobStreamIntegrationTest.findJobIdsReadsWithoutClaiming` |
| 대기 만료 작업을 페이지로 이어 처리, 한 번 1,000건 상한 | `ChatJobWorkerTest.expiresQueuedJobsInPagesUpToLimit` |
| 동시 실행 수 1, 새 메시지가 없는 동안 회수 | `ChatJobWorkerTest.singleSlotWorkerRecoversWhileWaitingForNewMessages` |
| 첫 1,000개 뒤 미발행 작업 | `ChatJobWorkerTest.republishScansBeyondFirstThousandPublishedJobs` |
| heartbeat가 있는 정상 실행의 회수 방지 | `ChatJobStreamIntegrationTest.heartbeatProtectsLiveJobAndOldOwnerCannotRenewIt` |
| 복구 발행 실패, 이전 예약, 다중 워커 동시 발행 | `ChatJobStreamIntegrationTest`의 atomic publish 테스트 |
| 정리 전에 만료 작업 선점 | `ChatJobStreamIntegrationTest.expiredQueuedJobCannotBeClaimedBeforeCleanupRuns` |
| 무한 도구 호출 | `OpenAiCompatibleLlmClientHttpTest.repeatedToolRequestsStopEvenWhenModelIgnoresToolLimitErrors` |
| API 정상·워커 장애 구분 | `ServiceOperationMonitoringServiceTest.workerFailureAndQueueAreVisibleWhileBackendRemainsUp` |

```powershell
.\gradlew.bat test --console=plain
```

통합 테스트는 Testcontainers PostgreSQL·Redis와 모의 모델을 사용합니다. 실제 GPU 처리량, 원격 모델 취소, 네트워크 분할의 모든 조합을 검증하는 부하 테스트는 아닙니다.

인증 필터의 접근 토큰 폐기 기록 조회는 Redis 재연결을 오래 기다리지 않도록 해당 GET에만 한도(`JWT_REVOCATION_LOOKUP_TIMEOUT`, 기본 200ms)를 적용합니다. 시간 초과 시 구독을 취소하고, 로그아웃 여부를 모르는 상태라 `JWT-010`(503)으로 거부합니다. 바로 실패하는 Redis 연결 오류는 기존처럼 차단 없이 진행합니다. JWT·사용자·작업 소유권 검사는 유지하며 Redis 복구 뒤에는 폐기 기록을 다시 조회합니다. 공용 Redis·Worker 시간 제한은 변경하지 않습니다.

## 배포와 관측

1. V31을 수정하지 않고 V33으로 요청·결과(JSONB)·heartbeat·updated_at·처리 단계·마감 시각 컬럼과 인덱스를 함께 추가합니다. develop의 V32(FAQ 질문 유사표현)와 번호가 겹치지 않게 V33을 씁니다. 운영 DB에는 정상 배포 시 Flyway로 적용합니다.
2. 기존 워커의 작업을 비운 뒤 교체합니다. heartbeat를 모르는 이전 워커와 새 워커를 장기간 혼용하지 않습니다. 롤백 전에도 진행 중 작업을 비우고 프론트 API 계약을 맞춥니다.
3. 배포 스크립트의 API·워커 health check를 확인합니다. 워커 health는 소비 스레드와 복구 루프 상태도 확인합니다.
4. 배포 Prometheus는 `ai-worker:8080`을 별도로 수집합니다. 운영 개요 API는 기존 필드를 유지하며 `services.worker`와 `worker` 지표를 추가합니다. 기존 backend JVM 시계열은 API 프로세스만 조회합니다.

### Worker 로그

기존 `작업ID(jobId)`는 질문 접수 응답·답변 조회에 쓰는 작업 번호이고, `시도ID(attemptId)`는 그 작업에 연결된 `answer_attempts_history` 기록 번호입니다. 두 값을 새로 만들지 않고 로그에 함께 남겨 API 접수·Worker 실행·DB 기록을 연결합니다. 모델 계산이 실행되는 별도 가상 스레드에도 이 두 값만 MDC로 연결하고 계산 종료 시 해제합니다. 계산 스레드의 로그(LLM 재시도·답변 생성 실패 등)는 메시지에 작업ID를 직접 넣지 않고, `logging.pattern.correlation`이 `[작업ID=45 시도ID=12] `처럼 줄 앞에 붙입니다. MDC 값이 없는 로그에는 아무것도 붙지 않습니다.

| 수준 | 기록 내용 |
|---|---|
| INFO | Worker 시작 설정·종료, 작업 시작과 마감 시각, DB 결과 저장 후 답변 저장 여부·실행 시간, 모델 연결·스트림 읽기 복구 성공 |
| WARN | 모델 연결 실패의 오류 코드·예외 종류·HTTP 상태·재시도 횟수·대기 시간, LLM 서버 설정 오류(4xx), 마감 초과(Worker가 계산을 취소함), 실행 권한 상실, Redis 결과 캐시·ACK·heartbeat 오류 |
| ERROR | 작업 선점·결과 저장 실패 등 자동 복구가 필요한 예외. 메시지를 지운 스택트레이스(`(메시지 생략)`)를 함께 남김 |
| DEBUG | 작업 선점 시 큐 대기 시간, DB에 반영된 진행 단계, ACK된 스트림 메시지 정리, 같은 스트림 읽기 장애의 반복 시도, 작업 조회 실패의 상세 원인·실행 중 조회 수 |

모델 재시도 횟수는 해당 HTTP 요청 기준입니다. 한 작업의 재시도 전체는 줄 앞 `[작업ID=…]`로 모아 봅니다. 재시도하지 않은 오류는 `ChatJobProcessor`가 최종 오류 코드로 남깁니다. `답변저장=true`는 DB 결과 저장이 끝났다는 뜻이며 뒤따르는 Redis 캐시·ACK 성공을 뜻하지 않습니다. DB 저장 실패나 실행 권한 상실로 결과를 기록하지 못했을 때는 저장 완료 로그를 남기지 않습니다. 실행 시간은 Worker 실행부터 DB 결과 저장까지이며 접수 HTTP 왕복 시간과 구분합니다.

새 로그에는 질문·답변·토큰·좌표를 담지 않습니다. Worker 예외는 예외 클래스·최하위 원인 클래스만 기록하고, 모델 HTTP 오류 응답 본문도 기록하지 않습니다. 평상시 빈 폴링이나 정상 heartbeat마다 INFO 로그를 남기지 않습니다.

작업 GET의 `503 / CHAT-025`는 기존 `GlobalExceptionHandler`에서 WARN으로 한 번 기록합니다. `ChatJobReadExecutor`는 DEBUG에 원인(`deadline`, `capacity`, 예외 종류 등)과 실행 중 조회 수를 남깁니다. 필요한 경우 이 클래스만 DEBUG로 설정해 상세 원인을 확인합니다. Redis 토큰 폐기 조회의 기존 WARN은 유지합니다.

Worker 안의 모델 HTTP 실패는 `LlmRecovery`가 상태·원인·재시도 정보를 함께 기록하므로 HTTP 클라이언트에서 같은 경고를 다시 남기지 않습니다. Worker 밖의 호출은 기존 HTTP 오류 로그를 유지합니다. 스트림 읽기는 같은 예외 종류가 반복되는 동안 첫 WARN 이후 DEBUG로 기록하고, 정상 읽기가 재개되면 INFO를 한 번 남깁니다. 원인이 바뀌거나 복구 뒤 다시 실패하면 WARN을 다시 기록합니다. Worker 내부 예외는 API 핸들러까지 전달되지 않으므로 실패 코드·예외 종류를 해당 처리 지점에서 기록합니다.

기본 INFO 로그에서 특정 작업을 찾는 예시입니다. 배포 Compose 디렉터리에서 실행하며 실제 응답의 jobId로 바꿉니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml logs --no-color --since 10m ai-worker | grep -E '작업ID=45([, ]|$)'
```

### 주요 Prometheus 지표

- `chat_worker_ready`: 소비 스레드와 복구 루프 상태
- `chat_jobs_queued`, `chat_jobs_oldest_queued_seconds`: 대기 수·최장 대기 시간. 모든 워커가 같은 DB를 보므로 집계에는 `max`를 사용합니다.
- `chat_worker_active`, `chat_worker_completed_total{status, code}`: 실행 수·결과. `code`는 실패 코드(성공은 `none`)
- `chat_worker_execution_seconds_*`, `chat_worker_timeouts_total`: 실행 시간(histogram으로 p95 계산)·전체 실행 시간 초과
- `chat_worker_model_retry_waits_total`: 일시적 모델 연결 실패 뒤 재시도를 위해 기다린 횟수. 최종 실패 수와 구분합니다. `llm_requests_seconds_*`는 복구 대기를 포함한 논리 호출 시간입니다.
- `chat_jobs_queue_wait_seconds_*`: 접수부터 선점까지 걸린 시간
- `chat_worker_reclaimed_total{reason}`, `chat_worker_queue_expired_total`, `chat_worker_discarded_total`: 회수(heartbeat 끊김·ACK 지연)·대기 만료·이전 실행의 폐기
- `chat_worker_terminated_total{reason}`: 전달 횟수 소진·메시지 없는 실행으로 실패 처리한 작업
- `chat_worker_lease_lost_total`, `chat_worker_heartbeat_failures_total`: 다른 실행에 넘어가 멈춘 실행, heartbeat 갱신 오류

LLM·검색·임베딩·DB·Redis 지표와 운영 개요 API 필드는 [운영 지표 Reference](../reference/metrics.md)를 참고하세요.

조회는 1~2초 간격으로 시작하고 429·5xx·네트워크 오류에는 백오프를 적용합니다. Nginx 조회 전용 한도는 IP당 20회/초, burst 40입니다. 회원 폴링도 DB·Redis 인증 조회를 발생시키므로 워커 증설 시 DB 풀과 모델 서버 용량을 함께 점검합니다.

기존 메모리 게스트 세션은 API 재시작 시 유실될 수 있습니다. 회원은 jobId를 보관하면 재조회할 수 있습니다. V33 이전 작업은 저장되지 않았던 좌표·지도 정보를 소급 복원할 수 없으며, 정확한 결과 캐시도 없는 성공 작업은 다른 질문의 답변을 추정하는 대신 저장소 오류를 반환합니다.
