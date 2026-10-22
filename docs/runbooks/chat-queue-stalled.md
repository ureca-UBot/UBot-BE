# Chat Queue Stalled (`UBotChatQueueStalled`)

가장 오래 기다린 Chat Job의 대기 시간(`chat_jobs_oldest_queued_seconds`)이 `ALERT_CHAT_QUEUE_OLDEST_SECONDS`초를 넘은 상태가 `ALERT_CHAT_QUEUE_FOR` 동안 이어졌습니다.

## 영향

- 사용자가 답변을 오래 기다립니다. 접수 뒤 10분 안에 시작하지 못한 질문은 CHAT-016으로 끝납니다.

## 확인

1. Worker가 살아 있는지: `chat_worker_ready`. 0이면 [Chat Worker Down](chat-worker-down.md)부터 봅니다.
2. Worker가 꽉 찼는지: `chat_worker_active`가 `CHAT_WORKER_CONCURRENCY`와 같은지, 대기 수 `chat_jobs_queued`
3. 모델 서버가 느린지: LLM 호출 p95(`llm_requests_seconds`), 차례 대기 `llm_concurrency_waiting`, 시간 초과 비율
4. GPU 상태: NLB 대상 그룹 health, 스폿 ASG 대수, GPU 사용률(`CWAgent nvidia_smi_utilization_gpu`)
5. 메시지가 멈췄는지: `redis_stream_group_messages_pending`, `chat_worker_reclaimed_total` 증가 여부

## 조치

- 요청이 몰린 것이면 GPU를 늘리거나(스폿 ASG) 잠시 기다립니다.
- Worker가 꽉 찼는데 모델 서버가 한가하면 Worker 수나 `CHAT_WORKER_CONCURRENCY`를 늘립니다. 늘리기 전에 DB 풀과 모델 서버 용량을 함께 봅니다.
- Worker가 멈춘 듯하면 Worker를 다시 시작합니다. 실행 중이던 작업은 대기열로 돌아가거나 1분 뒤 넘겨받아 다시 실행됩니다.

## 복구 확인

1. `chat_jobs_oldest_queued_seconds`가 기준값 아래로 내려감
2. 새 테스트 질문이 `SUCCEEDED`로 끝남

## 금지

- 대기 작업을 줄이려고 Redis Stream이나 `chat_jobs`를 직접 지우는 것
- 원인 확인 없이 consumer group 삭제
