# Redis Down (`UBotRedisDown`)

redis-exporter가 응답하지 않거나(`up{job="redis"} == 0`), exporter가 Redis에 연결하지 못하는(`redis_up == 0`) 상태가 `ALERT_DEPENDENCY_DOWN_FOR` 넘게 이어졌습니다.

## 영향

- 답변 작업 메시지 발행·소비(`chat:jobs`)가 멈춥니다. 작업은 DB에 남아 있어 Redis가 돌아오면 Worker가 다시 발행합니다.
- 로그인 실패 횟수, 로그아웃 차단, 채팅 사용량 제한, 랭킹, 스케줄러 잠금(ShedLock)이 동작하지 않습니다.

## 확인

1. `redis_up`과 `up{job="redis"}` 중 어느 쪽이 0인지. exporter만 죽었으면 Redis는 정상일 수 있습니다.
2. Redis 서버(배포는 State 서버) 접속 가능 여부
3. 메모리: `redis_memory_used_bytes`, `redis_memory_max_bytes`(0이면 상한 없음)
4. Redis 로그의 마지막 오류(메모리 부족, 영속성 파일 쓰기 실패 등)

AWS monitoring Compose는 현재 `chat:jobs` Stream 선택 옵션을 redis-exporter에 주지 않습니다. 이 환경에서 Stream length·PEL·lag 지표가 없다는 이유만으로 큐가 비었다고 판단하지 말고 DB 작업 상태와 `XLEN`·`XPENDING`을 함께 확인합니다.

## 조치

- exporter만 죽었으면 exporter를 다시 시작합니다.
- Redis가 멈췄으면 원인을 해결하고 Redis를 다시 시작합니다. 스트림을 잃었어도 대기 중 작업은 DB 기준으로 다시 발행되고, 죽은 Worker의 실행은 마지막 heartbeat 10분 뒤 실패로 끝납니다.
- 현재 공통 Compose는 named volume을 쓰지만 AOF, 별도 `redis.conf`, 인증과 TLS를 선언하지 않습니다. 볼륨 존재만으로 최근 Stream 보존을 보장하지 않으며 외부 공개 Redis 구성으로 사용하지 않습니다.

## 복구 확인

1. `redis_up == 1`
2. `redis_stream_group_messages_pending{stream="chat:jobs"}`가 쌓이지 않고 줄어듦
3. 새 테스트 질문이 `SUCCEEDED`로 끝남

## 금지

- `FLUSHALL`·`FLUSHDB`, `chat:jobs` 삭제
- 원인 확인 없이 consumer group(`chat-workers`) 삭제
