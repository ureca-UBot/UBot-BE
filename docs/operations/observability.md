# 관측과 진단

운영 화면이나 지표 하나만 보고 정상으로 판정하지 않습니다. 먼저 알고 싶은 운영 질문을 정하고, 지표·로그·DB/Redis 상태를 함께 확인합니다.

## 운영 질문에서 증거로

| 운영 질문 | 우선 지표 | 로그·추가 조회 | 정상화 기준 |
|---|---|---|---|
| API가 요청을 받고 있는가 | `up{job="ubot-backend"}` | Nginx 상태·Backend health·요청 오류 | health와 공개 API 성공 |
| Worker가 작업을 소비하는가 | `chat_worker_ready`, `chat_worker_active` | `ChatJobWorker`, `ChatJobRunner` 로그 | ready 1, 새 작업의 단계 진행 |
| 큐가 밀리는가 | `chat_jobs_queued`, `chat_jobs_oldest_queued_seconds` | DB `chat_jobs` 상태, Redis `XLEN`·`XPENDING` | 최장 대기 시간이 지속해서 감소 |
| 어느 단계가 느린가 | queue wait·Worker execution·LLM·embedding histogram | 작업의 `phase`, 작업ID·시도ID 로그 | 지연 구간의 p95와 backlog 감소 |
| 모델 장애가 복구 중인가 | LLM outcome·timeout·model retry wait | `WAITING_FOR_MODEL`, 재시도 횟수·HTTP 상태 | 모델 성공과 기존 작업 완료 |
| 검색 근거가 부족한가 | retrieval 결과·`NO_FAQ` 비율 | FAQ 후보·임계값·Profile 확인 | 기대 FAQ가 현재 Profile 검색에 노출 |
| DB가 병목인가 | `pg_up`, Hikari active·pending, connection count | PostgreSQL 로그·장기 트랜잭션 | pending 감소, 조회·저장 성공 |
| Redis가 신호를 전달하는가 | `redis_up`, stream pending·lag | Redis 로그, `XLEN`, `XPENDING` | 소비 재개와 DB 대기 작업 감소 |
| 가드가 요청을 과도하게 막는가 | 입력·출력 가드 outcome·reject | 가드 판정 코드와 Guard Review 조회 | 승인된 기준에서 오류·거절률 안정 |

전체 지표명과 PromQL은 [운영 지표 Reference](../reference/metrics.md)에 있습니다.

## 작업 로그 연결

채팅 접수 응답의 `jobId`는 Worker 로그의 `작업ID`, 답변 시도 기록은 `시도ID`로 연결합니다. 모델 계산 가상 스레드도 같은 MDC를 사용합니다.

```text
[작업ID=45 시도ID=12] ...
```

- INFO: 작업 시작·마감, DB 결과 저장, 복구 성공처럼 상태가 바뀐 사건
- WARN: 재시도 가능한 모델·Redis·heartbeat 문제, 마감 초과, 실행 권한 상실
- ERROR: 작업 선점·결과 저장처럼 자동 복구에 영향을 주는 예상 밖 실패
- DEBUG: 선점 대기, 단계 갱신, ACK 정리와 반복 장애의 세부 원인

질문·답변·토큰·좌표와 모델 HTTP 오류 본문은 운영 로그에 남기지 않습니다. `답변저장=true`는 DB 저장 완료를 뜻하며 Redis 캐시와 ACK 완료까지 의미하지 않습니다.

## 읽기 전용 진단

배포 디렉터리에서 현재 사용하는 Compose 파일을 지정해 실행합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml ps
docker compose -f docker-compose.yml -f docker-compose.deploy.yml logs --no-color --since 15m backend ai-worker
curl -fsS http://127.0.0.1:8080/actuator/health
```

특정 작업을 찾을 때는 실제 `jobId`로 바꿉니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml logs --no-color --since 15m ai-worker \
  | grep -E '작업ID=45([, ]|$)'
```

Redis Stream 지표가 수집되지 않는 환경에서는 지표가 없다는 사실을 큐가 비었다는 뜻으로 해석하지 않습니다. DB 작업 상태와 아래 조회를 함께 봅니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy.yml exec redis redis-cli XLEN chat:jobs
docker compose -f docker-compose.yml -f docker-compose.deploy.yml exec redis redis-cli XPENDING chat:jobs chat-workers
```

명령이 실패하면 그 결과도 증거입니다. 정상으로 간주하지 말고 Backend·State 서버 위치, Compose 프로젝트명과 네트워크를 먼저 확인합니다.

## 알림에서 런북으로

| 알림 | 첫 확인 | 런북 |
|---|---|---|
| `UBotBackendDown` | Backend health, 직전 배포, DB·Redis 동시 알림 | [Backend Down](../runbooks/backend-down.md) |
| `UBotWorkerDown` | Worker ready, queue age, OOM·모델 상태 | [Chat Worker Down](../runbooks/chat-worker-down.md) |
| `UBotChatQueueStalled` | queue age, active Worker, DB·Redis·모델 | [Queue Stalled](../runbooks/chat-queue-stalled.md) |
| `UBotLlmTimeoutHigh` | 모델 지연·timeout·GPU 상태 | [LLM Timeout High](../runbooks/llm-timeout-high.md) |
| `UBotPostgresDown` | exporter와 DB 자체 장애 구분 | [PostgreSQL Down](../runbooks/postgres-down.md) |
| `UBotRedisDown` | exporter와 Redis 자체 장애 구분 | [Redis Down](../runbooks/redis-down.md) |

Discord·Slack 전송과 Alertmanager 적용 방법은 [알림 구성](../how-to/alerting.md)을 참고합니다.

## 판정할 때 남길 정보

- 발생·탐지·복구 시각과 시간대
- 배포 이미지 digest, Git commit, Compose 파일과 주요 설정
- 영향받은 API·작업 수와 사용자 영향
- 작업ID·시도ID, 애플리케이션 오류 코드
- 마지막 정상 상태와 처음 확인한 비정상 상태
- 실행한 조치, 결과, 부작용과 남은 위험

민감한 요청 본문과 인증 정보는 진단 기록에 복사하지 않습니다.
