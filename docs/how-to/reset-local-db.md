# 로컬 DB 초기화와 기본 데이터 시드

PowerShell을 켜서 프로젝트 루트에서 다음 명령을 실행합니다.

```powershell
.\tools\reset-local-db.ps1
```

FAQ와 FAQ 카테고리만 CSV 기준으로 다시 넣고, 사용자·매장·채팅 등 나머지 데이터는 유지하려면
다음 명령을 실행합니다.

```powershell
.\tools\reset-faq-data.ps1
```

`faq`, `faq_question_variants`, `faq_embeddings` 세 테이블만 교체하고 다른 테이블을 유지하려면 다음 명령을 실행합니다.

```powershell
.\tools\reset-faq-tables.ps1
```

세 테이블 전용 명령은 기존 카테고리·ADMIN 계정·Ollama 임베딩 프로필을 사용합니다. FAQ 이력, 검색 로그 또는 미응답 기록이 FAQ를 참조하면 데이터 보호를 위해 중단합니다.

Docker Desktop이 실행 중이고 `.env` 파일이 준비되어 있어야 합니다.
시드에는 `baseline-faqs-1.csv`부터 `-5.csv`까지 FAQ 파일 5개와
`baseline-faq-variants-1.csv`부터 `-5.csv`까지 유사 질문과 벡터 파일 5개가 필요합니다.
변경 방법은 [시드 데이터 안내](../../src/main/resources/seed/README.md)를 참고하세요.

이 명령은 PostgreSQL 데이터 볼륨을 삭제한 뒤 Flyway 마이그레이션을 적용하고 기본 데이터를 생성합니다. 기존 PostgreSQL 데이터는 복구할 수 없으므로 필요한 경우 먼저 백업합니다. Ollama 모델, Redis, Prometheus 볼륨은 삭제하지 않습니다.

`reset-faq-data.ps1`은 FAQ, FAQ 카테고리, FAQ 이력, FAQ 검색 로그, variant와 FAQ 임베딩만 초기화합니다.
미응답 질문 데이터는 유지하되 이전 FAQ를 가리키던 참조만 해제합니다. 기본 ADMIN 계정이 필요하므로,
처음 실행하는 환경에서는 먼저 `reset-local-db.ps1`을 실행해야 합니다.

생성되는 기본 데이터는 다음과 같습니다.
FAQ 및 카테고리가 추가될때마다 제가 갱신시킬 예정입니다.

- FAQ 카테고리 17개와 FAQ 1,000개
- FAQ variant 5,000개와 미리 생성된 `QUESTION_ANSWER` 벡터 5,000개
- `user@user.com` / `12345678` / `USER`
- `admin@admin.com` / `12345678` / `ADMIN`

실행이 끝나면 Compose가 PostgreSQL 컨테이너를 계속 실행합니다. 벡터가 CSV에 있으므로 초기화할 때 Ollama를 실행할 필요는 없습니다. 애플리케이션은 평소처럼 `./gradlew bootRun` 또는 배포 Compose 설정으로 시작합니다.
