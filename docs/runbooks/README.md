# 장애 대응 런북

런북은 알림을 없애는 명령 모음이 아니라, 사용자 영향과 데이터 안전을 확인하면서 원하는 상태로 복구하는 절차입니다.

## 공통 순서

1. 알림이 가리키는 서비스와 실제 사용자 영향을 확인합니다.
2. 변경 전에 배포 revision, 컨테이너 상태, 지표와 최근 로그를 읽기 전용으로 수집합니다.
3. exporter 장애와 실제 의존 서비스 장애를 구분합니다.
4. 하위 의존성부터 복구합니다. DB·Redis·모델 장애가 Backend·Worker 알림을 함께 만들 수 있습니다.
5. 조치 뒤 health만 보지 말고 실제 테스트 요청이나 기존 작업이 끝나는지 확인합니다.
6. 복구하지 못하거나 원인이 바뀌면 반복 실행을 멈추고 수집한 증거와 함께 확대 대응합니다.

## 안전 기준

| 조치 | 변경성 | 반복 실행 | 기본 판단 |
|---|---|---|---|
| health·지표·로그·컨테이너 상태 조회 | 읽기 전용 | 반복 가능 | 가장 먼저 실행 |
| 단일 애플리케이션 컨테이너 재시작 | 상태 변경 | 조건부 반복 | DB 커밋·Worker 회수 정책을 확인한 뒤 실행 |
| 이전 이미지로 롤백 | 상태 변경 | 조건부 | Flyway·저장 데이터 호환성을 먼저 확인 |
| Redis group·Stream 변경 | 데이터 변경 | 위험 | 런북에 명시된 경우가 아니면 중단 |
| DB volume·데이터 디렉터리 변경 | 파괴적 | 되돌리기 어려움 | 운영 장애 대응에서 실행하지 않음 |
| 적용된 Flyway 파일 수정 | 비호환 | 실행 금지 | 새 마이그레이션으로 수정 |

## 장애별 문서

| 증상 | 사용자 영향 | 원하는 복구 상태 | 런북 |
|---|---|---|---|
| Backend 미응답 | 로그인·접수·조회 실패, Nginx 502 | Backend health와 공개 API 정상 | [Backend Down](backend-down.md) |
| Worker 미응답 | 질문은 접수되지만 답변 지연·만료 | Worker ready, backlog 감소, 새 작업 완료 | [Chat Worker Down](chat-worker-down.md) |
| 대기열 정체 | 답변 대기 시간이 계속 증가 | 최장 대기 시간 감소 | [Queue Stalled](chat-queue-stalled.md) |
| LLM timeout 증가 | 실행 시간 초과·실패 증가 | 모델 지연과 timeout 비율 정상화 | [LLM Timeout High](llm-timeout-high.md) |
| PostgreSQL 장애 | 대부분의 API와 Worker 저장 실패 | `pg_up == 1`, pool 대기 감소 | [PostgreSQL Down](postgres-down.md) |
| Redis 장애 | 큐 신호·rate limit·토큰 폐기·랭킹 저하 | `redis_up == 1`, 재발행·소비 재개 | [Redis Down](redis-down.md) |

## 중단·확대 조건

- 예상한 오류가 아니라 데이터 손상·마이그레이션 체크섬·권한 오류가 나타남
- 같은 재시작이나 롤백을 반복해도 health 또는 실제 요청이 정상화되지 않음
- DB 용량·연결 수·디스크 문제로 쓰기가 계속 실패함
- 이전 이미지가 현재 스키마를 읽지 못함
- Worker 회수와 모델 재시도가 접수 후 전체 기한을 계속 소진함

이 경우 변경을 더 반복하지 말고 [관측과 진단](../operations/observability.md)의 정보를 보존합니다. 시스템의 정상·저하·복구 동작은 [신뢰성 모델](../reliability-model.md)을 기준으로 해석합니다.
