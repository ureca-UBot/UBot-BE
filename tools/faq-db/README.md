# FAQ DB 도구

프로젝트 루트에서 실행합니다. 시드 CSV와 날짜별 추가 적재 목록은 [seed](seed/README.md)에 있습니다.

| 작업 | 명령 |
| --- | --- |
| 시드 검증 | `python -X utf8 .\tools\faq-db\validate_baseline_seed.py` |
| 로컬 DB 전체 초기화 | `.\tools\faq-db\reset-local-db.ps1` |
| FAQ 데이터 초기화 | `.\tools\faq-db\reset-faq-data.ps1` |
| FAQ 테이블만 교체 | `.\tools\faq-db\reset-faq-tables.ps1` |
| 추가 FAQ CSV 준비 | `python -X utf8 .\tools\faq-db\prepare_additional_faq.py 날짜` |
| 추가 FAQ DB 적재 | `.\tools\faq-db\import-additional-faq.ps1 날짜` |
| 임베딩 백필 | `.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>` |

초기화 범위와 실행 조건은 [로컬 DB 초기화 안내](../../docs/how-to/reset-local-db.md)를 참고하세요. SQL 파일과 `faq-seed-common.ps1`은 위 명령에서 호출합니다.
