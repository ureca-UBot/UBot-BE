# FAQ DB 도구

프로젝트 루트에서 실행합니다. FAQ·사용자·시연 시드는 [seed](seed/README.md)에 있습니다.

| 작업 | 명령 |
| --- | --- |
| 시드 검증 | `python -X utf8 .\tools\faq-db\validate_baseline_seed.py` |
| 로컬 DB 전체 초기화 | `.\tools\faq-db\scripts\reset-local-db.ps1` |
| FAQ 데이터 초기화 | `.\tools\faq-db\scripts\reset-faq-data.ps1` |
| 시연 활동 데이터 적용 (상품 의도 FAQ 포함) | `.\tools\faq-db\scripts\apply-demo-activity.ps1` |
| 시연 활동 데이터 적용 (BE EC2, 운영 DB) | release 폴더에서 `bash tools/faq-db/scripts/apply-demo-activity.sh [--force]` |
| 상품 의도 FAQ만 적용 (시연 활동을 이미 넣은 DB) | `.\tools\faq-db\scripts\apply-demo-activity.ps1 -ProductFaqsOnly` / 운영: `bash tools/faq-db/scripts/apply-demo-activity.sh --product-faqs-only` |
| 시연 활동 데이터 초기화 | `.\tools\faq-db\scripts\clear-demo-activity.ps1` |
| 임베딩 백필 | `.\tools\faq-db\backfill-embeddings.ps1 -Email <관리자 이메일>` |
| 답변 문장 검사 | `python -X utf8 .\tools\faq-db\check_sentence_gate.py --ids <FAQ ID>` |

초기화 범위와 실행 조건은 [로컬 DB 초기화 안내](../../docs/how-to/reset-local-db.md)를 참고하세요. `scripts/`에는 실행 명령을, `seed/`에는 입력 데이터와 SQL을, `lib/`에는 공통 함수를 둡니다. FAQ 변경은 기준 CSV를 수정한 뒤 FAQ 초기화 스크립트로 반영합니다.
