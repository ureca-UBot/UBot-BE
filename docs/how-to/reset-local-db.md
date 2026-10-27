    # 로컬 DB 초기화와 기본 데이터 시드

PowerShell을 켜서 프로젝트 루트에서 다음 명령을 실행합니다.

```powershell
.\tools\faq-db\scripts\reset-local-db.ps1
```

FAQ·FAQ 카테고리·관련 이력과 로그를 초기화한 뒤 CSV 기준으로 다시 넣고, 사용자·매장·채팅 등 나머지 데이터는 유지하려면
다음 명령을 실행합니다.

```powershell
.\tools\faq-db\scripts\reset-faq-data.ps1
```

Docker Desktop이 실행 중이고 `.env` 파일이 준비되어 있어야 합니다.
시드에는 `baseline-faqs.csv`와 `baseline-faq-variants.csv`가 필요합니다. 스크립트는 두 CSV를 검증한 뒤 적재합니다.
변경 방법은 [시드 데이터 안내](../../tools/faq-db/seed/README.md)를 참고하세요.

`reset-local-db.ps1`은 PostgreSQL 데이터 볼륨을 삭제한 뒤 Flyway 마이그레이션과 기본 데이터를 적용합니다. 인기 FAQ·FAQ 대시보드·통신 지도용 시연 활동 데이터는 넣지 않습니다. 기존 PostgreSQL 데이터는 복구할 수 없으므로 필요한 경우 먼저 백업합니다. Ollama 모델, Redis, Prometheus 볼륨은 삭제하지 않습니다.

기본 DB에 이 브랜치의 시연 활동 데이터를 추가하려면 다음을 실행합니다. 이미 질문 로그가 있으면 중복 적재를 막기 위해 실패하므로, 필요하면 먼저 시연 활동 초기화를 실행한 뒤 시드를 적용하세요.

```powershell
.\tools\faq-db\scripts\clear-demo-activity.ps1
.\tools\faq-db\scripts\apply-demo-activity.ps1
```

`reset-faq-data.ps1`은 FAQ, FAQ 카테고리, FAQ 이력, FAQ 검색 로그, variant와 FAQ 임베딩만 초기화합니다.
미응답 질문 데이터는 유지하되 이전 FAQ를 가리키던 참조만 해제합니다. 기본 ADMIN 계정이 필요하므로,
처음 실행하는 환경에서는 먼저 `reset-local-db.ps1`을 실행해야 합니다.

생성되는 FAQ·카테고리·variant 건수는 CSV의 내용에 따라 결정됩니다. 벡터는 적재되지 않습니다. 기본 계정은 다음과 같습니다.

- `user@user.com`, `user2@user.com` ~ `user10@user.com` / `12345678` / `USER`
- `ubot.demo.001@example.test` ~ `ubot.demo.300@example.test` / `UbotDemo2026!` / `USER` (가상 사용자 300명)
- `admin@admin.com` / `12345678` / `ADMIN`

상품 카탈로그(요금제·부가서비스·결합·로밍·인터넷/IPTV)는 Flyway `V47`이 넣습니다. 사용자는 다음 두 파일로 회선·부가서비스·결합·로밍과
사용량·청구·약정 기록을 가진 상태로 만들어집니다. `reset-faq-data.ps1`은 이 데이터를 건드리지 않습니다.

- `tools/faq-db/seed/users/seed-demo-users.sql`: 가상 사용자 300명. 기록 날짜는 2026년 7~10월로 고정되어 있습니다.
- `tools/faq-db/seed/users/seed-users.sql`: `user` 계정 10명. 기록 날짜는 초기화한 날 기준(사용량 최근 4개월, 청구 지난 3개월)입니다.

사용량의 `billing_month`는 사용한 달, 청구의 `billing_month`는 청구서가 나온 달(금액은 전월 사용분)입니다. 가상 사용자 300명의 청구 월도 `seed-users.sql`이 이 기준으로 옮깁니다.

벡터를 적재하기 위해서는 백엔드를 켜고 다음 명령어를 실행하세요.

- `.\tools\faq-db\backfill-embeddings.ps1 -Email admin@admin.com`
- `12345678 입력`

실행이 끝나면 Compose가 PostgreSQL 컨테이너를 계속 실행합니다. 초기화할 때 Ollama를 실행할 필요는 없습니다. 애플리케이션은 평소처럼 `./gradlew bootRun` 또는 배포 Compose 설정으로 시작하고, 검색 전에 FAQ `QUESTION` 벡터를 [백필](llm-module.md#백필)해야 합니다.
