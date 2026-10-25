# FAQ DB 도구

프로젝트 루트에서 실행합니다. 시드 CSV와 날짜별 추가 적재 목록은 [seed](seed/README.md)에 있습니다.

| 작업 | 명령 |
| --- | --- |
| 시드 검증 | `python -X utf8 .\tools\faq-db\validate_baseline_seed.py` |
| 로컬 DB 전체 초기화 | `.\tools\faq-db\reset-local-db.ps1` |
| FAQ 데이터 초기화 | `.\tools\faq-db\reset-faq-data.ps1` |
| 추가 FAQ DB 적재 | `.\tools\faq-db\import-additional-faq.ps1 날짜` |
| 기존 FAQ 수정·삭제 적재 | `.\tools\faq-db\update-existing-faq.ps1 날짜 [-DryRun]` |
| 임베딩 백필 | `.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>` |
| 답변 문장 검사 | `python -X utf8 .\tools\faq-db\check_sentence_gate.py --date 날짜` |

초기화 범위와 실행 조건은 [로컬 DB 초기화 안내](../../docs/how-to/reset-local-db.md)를 참고하세요. SQL 파일과 `faq-seed-common.ps1`은 위 명령에서 호출합니다.

DB 적재 명령은 추가 적재, 기존 FAQ 수정·삭제 적재, FAQ 데이터 초기화, 로컬 DB 전체 초기화의 네 가지입니다. 추가 적재와 수정 적재 대상은 시드 CSV와 `seed/days/additional-faq-날짜.json`·`seed/days/updated-faq-날짜.json`에 직접 기록하고, 삭제 대상은 `seed/deleted-faqs.json`에 기록합니다([시드 안내](seed/README.md)). 시드와 추가 FAQ 스크립트는 FAQ·질문 표현만 적재합니다. 검색에 필요한 `QUESTION` 벡터는 적재 후 백필합니다. FAQ 초기화 스크립트는 기존 FAQ 벡터를 삭제하지만 새 벡터를 만들지 않습니다. 두 초기화 스크립트는 `deleted-faqs.json`의 FAQ를 적재한 뒤 소프트 삭제합니다.

## 기존 DB에 FAQ 수정·삭제 반영

이미 FAQ가 적재된 DB에 시드 CSV의 수정분을 반영할 때는 초기화 대신 아래 순서로 실행합니다. `PRODUCT_DATA` 같은 새 의도가 들어 있으면 먼저 백엔드를 한 번 실행해 Flyway 마이그레이션을 적용해 둡니다.

1. 문장 검사: `python -X utf8 .\tools\faq-db\check_sentence_gate.py --date 날짜`로 수정·추가한 FAQ 답변에 variant마다 기준값(0.6) 이상인 문장이 있는지 확인합니다. 로컬 임베딩 API(기본 Ollama `bge-m3:567m`)를 호출하고 DB에는 쓰지 않습니다.
2. 미리 보기: `.\tools\faq-db\update-existing-faq.ps1 날짜 -DryRun`으로 반영·건너뛸 FAQ 목록을 확인합니다. 트랜잭션은 롤백됩니다.
3. 수정 적재: `.\tools\faq-db\update-existing-faq.ps1 날짜`
4. 추가 FAQ가 있으면 `.\tools\faq-db\import-additional-faq.ps1 날짜`. 추가 적재는 DB의 다음 ID를 쓰므로 날짜 순서대로 적재합니다.
5. 즉시 백필: `.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>`. 질문이나 variant가 바뀐 FAQ와 새 FAQ는 백필 전까지 검색되지 않습니다.
6. 백엔드를 재시작해 하위 질문 영어 어휘를 갱신합니다.

수정 적재 스크립트는 다음처럼 동작합니다.

- DB의 현재 값(카테고리·질문·답변·의도, 바뀌는 variant 문장)이 `updated-faq-날짜.json`의 `before` 값과 다르면 관리자가 고친 FAQ로 보고 건너뛴 뒤 목록으로 보고합니다. 추가 적재로 ID가 시드와 달라진 DB도 이 검사에서 걸러집니다. 이미 반영된 FAQ는 `already applied`로 건너뜁니다.
- 바꾸기 전 행을 `old_faq`에 기록하고 `version`을 올립니다(관리자 화면 수정과 같은 이력). 문장이 바뀌지 않은 variant의 벡터는 새 버전으로 옮기고, 문장이 바뀐 variant만 백필 대상으로 남깁니다.
- `deleted-faqs.json`의 FAQ는 `deleted_at`만 채웁니다. 이미 삭제됐거나 질문이 시드와 다른 FAQ는 건너뜁니다.
