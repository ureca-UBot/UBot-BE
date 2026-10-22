# 답변 Worker 장애 복구와 검증

## 보존하는 계약

- 질문·재시도·의도 재검색은 현재 비동기 계약인 `202 + jobId`를 유지합니다. 최종 상태는 `GET /chat/jobs/{jobId}`로 조회합니다. 예전 develop의 동기 응답을 사용하는 프론트는 이 계약으로 전환해야 합니다.
- 금지어, 회원·게스트 소유권, 게스트 질문 한도, 사용자 재시도 횟수, 재검색 중복 제한은 유지합니다.
- 대기 제한은 기본 10분, 실행 제한은 기본 180초입니다. 각각 `CHAT_WORKER_MAX_QUEUE_WAIT`, `CHAT_WORKER_EXECUTION_TIMEOUT`으로 설정합니다. 두 시간은 별개입니다.
- V33에서 처리 단계와 마감 시각을 DB에 저장해 접수·상태 조회 응답으로 전달합니다. 실행 제한은 선점 시각부터 적용하며 단계 전환으로 연장되지 않습니다. 장애로 실행을 넘겨받으면 기존 정책대로 새 실행 제한을 부여합니다. 단계 갱신은 실행 토큰과 RUNNING 상태를 검사하고, 저장 실패만으로 답변을 실패시키지는 않습니다. 사용자 문구와 시간 표시 방법은 [프론트 연동](frontend-integration.md#처리-단계와-제한-시간)을 참고하세요.
- DB 성공 커밋에 질문 로그 ID·매장 지도·위치 요청 정보를 함께 포함합니다. Redis 캐시 저장 전이나 캐시 유실 후에도 같은 결과를 조회합니다.
- 접수 당시 좌표·지역도 DB에 저장합니다. 복구된 메시지는 같은 요청 정보를 사용합니다.
- 요청 정보(`request_context`)는 성공·실패 확정 시 같은 DB 트랜잭션에서 NULL로 지웁니다. 대기·실행·재선점·종료 중 대기열 반환 시에는 보존하며, 완료 트랜잭션이 롤백되면 삭제도 롤백됩니다. 기존에 완료된 행을 소급 정리하지는 않습니다. 결과 지도(`result_payload`)의 중심점·매장 좌표는 답변 재조회에 필요하므로 유지하고, Redis 메시지는 기존 ACK 후 스트림 정리 정책을 따릅니다.

## 실행과 복구

- 새 메시지가 없으면 실행 슬롯을 반환한 뒤 쉽니다. 동시 실행 수 1에서도 복구가 슬롯을 사용할 수 있습니다.
- 실행은 DB heartbeat를 `CHAT_WORKER_HEARTBEAT_TIMEOUT`(기본 1분)의 3분의 1, 최대 15초 간격으로 갱신합니다. ACK가 늦어도 heartbeat가 살아 있으면 작업을 빼앗거나 실패 처리하지 않습니다.
- Worker가 죽으면 heartbeat가 1분 넘게 끊긴 작업의 메시지를 넘겨받습니다. 넘겨받기 전에 메시지 내용으로 작업을 확인해, heartbeat가 살아 있는 실행의 메시지는 건드리지 않습니다(전달 횟수가 늘지 않음). 5분(`CHAT_WORKER_RECLAIM_IDLE`) 넘게 ACK되지 않은 메시지는 작업 상태를 보지 않고 넘겨받기를 시도하는 마지막 안전망입니다.
- 실행 제한을 넘기면 계산 Future를 취소하고 타임아웃을 기록한 뒤 Worker 슬롯을 돌려줍니다. 인터럽트를 무시한 외부 호출이 늦게 반환해도 그 결과는 저장하지 않습니다. 이미 모델 서버에 전달된 계산을 원격에서 강제 중단한다는 보장은 없으므로 HTTP 호출 제한도 유지합니다.
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

## 배포와 관측

1. V31을 수정하지 않고 V33으로 요청·결과(JSONB)·heartbeat·updated_at·처리 단계·마감 시각 컬럼과 인덱스를 함께 추가합니다. develop의 V32(FAQ 질문 유사표현)와 번호가 겹치지 않게 V33을 씁니다. 운영 DB에는 정상 배포 시 Flyway로 적용합니다.
2. 기존 워커의 작업을 비운 뒤 교체합니다. heartbeat를 모르는 이전 워커와 새 워커를 장기간 혼용하지 않습니다. 롤백 전에도 진행 중 작업을 비우고 프론트 API 계약을 맞춥니다.
3. 배포 스크립트의 API·워커 health check를 확인합니다. 워커 health는 소비 스레드와 복구 루프 상태도 확인합니다.
4. 배포 Prometheus는 `ai-worker:8080`을 별도로 수집합니다. 운영 개요 API는 기존 필드를 유지하며 `services.worker`와 `worker` 지표를 추가합니다. 기존 backend JVM 시계열은 API 프로세스만 조회합니다.

주요 Prometheus 지표:

- `chat_worker_ready`: 소비 스레드와 복구 루프 상태
- `chat_jobs_queued`, `chat_jobs_oldest_queued_seconds`: 대기 수·최장 대기 시간. 모든 워커가 같은 DB를 보므로 집계에는 `max`를 사용합니다.
- `chat_worker_active`, `chat_worker_completed_total{status, code}`: 실행 수·결과. `code`는 실패 코드(성공은 `none`)
- `chat_worker_execution_seconds_*`, `chat_worker_timeouts_total`: 실행 시간(histogram으로 p95 계산)·전체 실행 시간 초과
- `chat_jobs_queue_wait_seconds_*`: 접수부터 선점까지 걸린 시간
- `chat_worker_reclaimed_total{reason}`, `chat_worker_queue_expired_total`, `chat_worker_discarded_total`: 회수(heartbeat 끊김·ACK 지연)·대기 만료·이전 실행의 폐기
- `chat_worker_terminated_total{reason}`: 전달 횟수 소진·메시지 없는 실행으로 실패 처리한 작업
- `chat_worker_lease_lost_total`, `chat_worker_heartbeat_failures_total`: 다른 실행에 넘어가 멈춘 실행, heartbeat 갱신 오류

LLM·검색·임베딩·DB·Redis 지표와 운영 개요 API 필드는 [운영 지표 Reference](../reference/metrics.md)를 참고하세요.

조회는 1~2초 간격으로 시작하고 429·5xx·네트워크 오류에는 백오프를 적용합니다. Nginx 조회 전용 한도는 IP당 20회/초, burst 40입니다. 회원 폴링도 DB·Redis 인증 조회를 발생시키므로 워커 증설 시 DB 풀과 모델 서버 용량을 함께 점검합니다.

기존 메모리 게스트 세션은 API 재시작 시 유실될 수 있습니다. 회원은 jobId를 보관하면 재조회할 수 있습니다. V33 이전 작업은 저장되지 않았던 좌표·지도 정보를 소급 복원할 수 없으며, 정확한 결과 캐시도 없는 성공 작업은 다른 질문의 답변을 추정하는 대신 저장소 오류를 반환합니다.
