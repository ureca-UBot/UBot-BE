# FAQ DB 도구

프로젝트 루트에서 실행합니다. 시드 CSV와 날짜별 추가 적재 목록은 [seed](seed/README.md)에 있습니다.

| 작업 | 명령 |
| --- | --- |
| 시드 검증 | `python -X utf8 .\tools\faq-db\validate_baseline_seed.py` |
| 로컬 DB 전체 초기화 | `.\tools\faq-db\reset-local-db.ps1` |
| FAQ 데이터 초기화 | `.\tools\faq-db\reset-faq-data.ps1` |
| 추가 FAQ DB 적재 | `.\tools\faq-db\import-additional-faq.ps1 날짜` |
| 임베딩 백필 | `.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>` |

초기화 범위와 실행 조건은 [로컬 DB 초기화 안내](../../docs/how-to/reset-local-db.md)를 참고하세요. SQL 파일과 `faq-seed-common.ps1`은 위 명령에서 호출합니다.

DB 적재 명령은 추가 적재, FAQ 데이터 초기화, 로컬 DB 전체 초기화의 세 가지입니다. 추가 적재 대상은 시드 CSV와 `seed/days/additional-faq-날짜.json`에 직접 기록합니다([시드 안내](seed/README.md)). 시드와 추가 FAQ 스크립트는 FAQ·질문 표현만 적재합니다. 검색에 필요한 `QUESTION` 벡터는 적재 후 백필합니다. FAQ 초기화 스크립트는 기존 FAQ 벡터를 삭제하지만 새 벡터를 만들지 않습니다.
