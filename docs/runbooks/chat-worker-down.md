# Chat Worker Down (`UBotWorkerDown`)

Worker 프로세스가 Prometheus 수집에 응답하지 않거나(`up == 0`), 떠 있지만 소비 스레드·복구 루프가 멈춘(`chat_worker_ready == 0`) 상태가 `ALERT_WORKER_DOWN_FOR` 넘게 이어졌습니다.

## 영향

- 새 질문은 `202`로 접수되지만 답변이 만들어지지 않습니다.
- 접수 뒤 10분(`CHAT_WORKER_MAX_QUEUE_WAIT`) 안에 시작하지 못한 질문은 CHAT-016으로 끝납니다.

## 확인

1. `chat_worker_ready`, `up{job="ubot-ai-worker"}`(로컬은 `ubot-backend`)
2. `ai-worker` 컨테이너 상태와 재시작 횟수, `docker inspect`의 `OOMKilled`
3. Worker 로그(`ChatJobWorker`)의 마지막 오류
4. Redis 처리 대기·lag: `redis_stream_group_messages_pending{stream="chat:jobs"}`, `redis_stream_group_lag`
5. PostgreSQL 상태: `pg_up`, `hikaricp_connections_pending`
6. 모델 서버 상태: NLB 대상 그룹 health, `llm_requests_seconds_count{outcome!="success"}`

## 조치

- 원인을 확인한 뒤 `ai-worker`를 다시 시작합니다. 정상 종료면 실행 중인 답변은 20초 안에 끝내거나 대기열로 돌려놓고, 강제 종료여도 1분 뒤 다른 Worker가 넘겨받습니다([Worker 장애 복구와 검증](../how-to/worker-reliability.md)).
- 같은 질문으로 3번 죽으면 그 작업은 CHAT-015로 끝납니다. 반복해서 죽으면 메모리·모델 응답을 먼저 확인합니다.

## 복구 확인

1. `chat_worker_ready == 1`
2. `chat_jobs_oldest_queued_seconds`가 줄어듦
3. 새 테스트 질문이 `SUCCEEDED`로 끝남

## 금지

- Redis Stream(`chat:jobs`) 전체 삭제·`XTRIM`
- 원인 확인 없이 consumer group(`chat-workers`) 삭제. 처리 대기 목록(PEL)이 함께 사라져 넘겨받을 수 없게 됩니다.
