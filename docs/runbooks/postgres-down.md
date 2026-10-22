# PostgreSQL Down (`UBotPostgresDown`)

postgres-exporter가 응답하지 않거나(`up{job="postgres"} == 0`), exporter가 PostgreSQL에 연결하지 못하는(`pg_up == 0`) 상태가 `ALERT_DEPENDENCY_DOWN_FOR` 넘게 이어졌습니다.

## 영향

- 질문 접수, 답변 저장, 로그인 등 DB를 쓰는 거의 모든 기능이 실패합니다.
- Worker는 작업을 시작하거나 결과를 기록하지 못하고, 메시지를 ACK하지 않고 남겨 둡니다.

## 확인

1. `pg_up`과 `up{job="postgres"}` 중 어느 쪽이 0인지. exporter만 죽었으면 DB는 정상일 수 있습니다.
2. DB 서버(배포는 State 서버) 접속 가능 여부와 디스크 여유
3. 연결 수: `pg_stat_activity_count`, Backend·Worker의 `hikaricp_connections_pending`. `DB_POOL_MAX_SIZE` 합이 `max_connections`보다 큰지
4. 오래 열린 트랜잭션: `pg_stat_activity_max_tx_duration`
5. PostgreSQL 로그의 마지막 오류

## 조치

- exporter만 죽었으면 exporter를 다시 시작합니다.
- DB가 멈췄으면 원인(디스크·메모리·연결 수)을 해결하고 PostgreSQL을 다시 시작합니다. Backend·Worker는 연결이 돌아오면 다시 붙습니다.

## 복구 확인

1. `pg_up == 1`
2. `hikaricp_connections_pending`이 0 근처로 돌아옴
3. 새 테스트 질문이 `SUCCEEDED`로 끝남

## 금지

- 볼륨 삭제(`docker compose down -v`), 데이터 디렉터리 직접 수정
- 적용된 Flyway 마이그레이션 파일 수정, psql로 extension 임의 삭제
