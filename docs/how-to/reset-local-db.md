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

Docker Desktop이 실행 중이고 `.env` 파일이 준비되어 있어야 합니다.
또한 `baseline-faqs.csv`에 `vector` 컬럼이 있어야 합니다. FAQ는 이 CSV를 직접 편집하며,
변경한 경우에는 먼저
`python tools/generate_faq_vectors.py --model bge-m3:567m --input-columns question`을
실행합니다.

이 명령은 PostgreSQL 데이터 볼륨을 삭제한 뒤 Flyway 마이그레이션을 적용하고 기본 데이터를 생성합니다. 기존 PostgreSQL 데이터는 복구할 수 없으므로 필요한 경우 먼저 백업합니다. Ollama 모델, Redis, Prometheus 볼륨은 삭제하지 않습니다.

`reset-faq-data.ps1`은 FAQ, FAQ 카테고리, FAQ 이력, FAQ 검색 로그, FAQ 임베딩만 초기화합니다.
미응답 질문 데이터는 유지하되 이전 FAQ를 가리키던 참조만 해제합니다. 기본 ADMIN 계정이 필요하므로,
처음 실행하는 환경에서는 먼저 `reset-local-db.ps1`을 실행해야 합니다.

생성되는 기본 데이터는 다음과 같습니다.
FAQ 및 카테고리가 추가될때마다 제가 갱신시킬 예정입니다.

- FAQ 카테고리 17개와 FAQ 1,000개
- CSV에 미리 생성된 FAQ 질문 벡터 1,000개
- `user@user.com` / `12345678` / `USER`
- `admin@admin.com` / `12345678` / `ADMIN`

실행이 끝나면 Compose가 PostgreSQL과 Ollama 컨테이너를 계속 실행합니다. 애플리케이션은 평소처럼 `./gradlew bootRun` 또는 배포 Compose 설정으로 시작합니다.
