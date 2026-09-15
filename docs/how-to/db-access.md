# DB 직접 조회하기

로컬 PostgreSQL에 접속해 데이터나 pgvector 상태를 확인하는 방법입니다.
애플리케이션 실행에는 필요하지 않은 선택 단계입니다.

전제: [quickstart](../quickstart.md)의 3단계까지 완료되어 Compose의 `postgres` 서비스가 실행 중이어야 합니다. 아래 명령은 프로젝트 루트에서 실행합니다. 테스트용 Testcontainers DB와는 별개입니다.

## psql로 접속하기

별도 설치 없이 컨테이너 안의 psql을 사용합니다.

```powershell
docker compose exec postgres psql -U ubot -d ubot
```

이 개발 이미지의 기본 로컬 소켓 인증에서는 비밀번호를 묻지 않습니다. 인증 설정을 별도로 바꿨다면 해당 설정을 따릅니다. `.env`에서 `POSTGRES_USER`나 `POSTGRES_DB`를 바꿨다면 `-U`, `-d` 값도 맞춰 바꾸세요.

종료: `\q`

## DBeaver로 접속하기

새 PostgreSQL Connection을 만들고 아래 값을 입력합니다.

| 항목 | 값 |
|---|---|
| Host | `localhost` |
| Port | `.env`의 `POSTGRES_PORT` (`.env.example`은 `15432`) |
| Database | `.env`의 `POSTGRES_DB` |
| Username | `.env`의 `POSTGRES_USER` |
| Password | `.env`의 `POSTGRES_PASSWORD` |

`Test Connection`이 성공하면 완료입니다.

## pgvector 활성화 확인

psql 또는 DBeaver에서 실행합니다.

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'vector';
```

`vector` 행이 한 줄 조회되면 정상입니다. 조회되지 않으면 [troubleshooting](../troubleshooting.md#vector-extension이-없음)을 참고하세요.

## 주의사항

- 테이블 구조를 DBeaver나 psql로 직접 변경하지 않습니다. 스키마 변경은 Flyway 마이그레이션으로 관리합니다. 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md#db-마이그레이션-flyway)를 참고하세요.
- JPA는 `ddl-auto: none`이지만 개발 프로필의 Spring AI pgvector는 `initialize-schema: true`로 벡터 테이블·인덱스 초기화를 수행합니다. 두 설정은 별개입니다.
- Testcontainers는 별도 컨테이너·DB·임의 호스트 포트를 사용합니다. 위의 개발 DB 접속 정보로 테스트 DB를 조회하거나 정리하지 않습니다.
