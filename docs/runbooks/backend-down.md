# Backend Down (`UBotBackendDown`)

Backend(`ubot-backend`)가 `ALERT_BACKEND_DOWN_FOR` 넘게 Prometheus 수집에 응답하지 않습니다.

## 영향

- 로그인·질문 접수·조회 등 모든 API가 실패할 수 있습니다. Nginx는 502를 돌려줍니다.
- 관리자 운영 모니터링 화면도 열리지 않습니다.

## 확인

1. `up{job="ubot-backend"}`, `backend` 컨테이너 상태와 재시작 횟수, `OOMKilled`
2. Backend 로그의 마지막 오류(기동 실패면 Flyway 마이그레이션·설정 오류부터)
3. `/actuator/health`(배포 서버 안에서만 접근 가능)
4. PostgreSQL·Redis 알림이 함께 왔는지. 의존 서비스가 먼저 죽었으면 그쪽 런북부터 봅니다.
5. 직전 배포가 있었는지(GitHub Actions 배포 기록)

## 조치

- 일시적인 오류면 `backend`를 다시 시작하고 Nginx도 다시 만듭니다. Nginx를 그대로 두면 이전 Backend 주소로 보내 502가 날 수 있습니다([배포](../deploy.md#주의할-점)).
- 직전 배포 뒤부터면 이전 이미지로 되돌립니다. 되돌릴 수 없는 마이그레이션이 포함된 배포는 이미지만 되돌려도 DB는 그대로입니다.

## 복구 확인

1. `up{job="ubot-backend"} == 1`
2. `/api/notices` 같은 공개 API가 200
3. 관리자 운영 모니터링 화면이 열림

## 금지

- `docker compose down -v`(DB·Redis·Prometheus 볼륨 삭제)
