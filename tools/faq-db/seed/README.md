# FAQ 시드 데이터

시드는 목적별로 나뉩니다.

```text
baseline/  FAQ·카테고리·질문 표현의 기준 CSV와 soft-delete 목록
users/     로컬 사용자·가입 상품·이용/청구 기록
demo/      상품 의도 FAQ, 인기 FAQ·FAQ 대시보드·통신 지도 시연 활동 SQL
```

`baseline/baseline-faqs.csv`와 `baseline/baseline-faq-variants.csv`가 FAQ 기준 데이터입니다. FAQ마다 원본 질문을 첫 variant로 저장하고, 유사표현은 필요한 만큼 추가할 수 있습니다.

```text
baseline/baseline-faqs.csv: faqId,category,question,answer,intent
baseline/baseline-faq-variants.csv: originFaqId,variantId,question
```

시드 CSV에는 임베딩 벡터가 없습니다. FAQ 검색에 필요한 `QUESTION` 벡터는 데이터를 적재한 뒤 생성해야 합니다.

FAQ ID는 기존 마지막 ID 다음부터 연속으로, variant ID도 기존 마지막 ID 다음부터 연속으로 지정합니다. 각 FAQ의 첫 variant 질문은 FAQ 원본 질문과 같아야 합니다. CSV를 직접 수정한 뒤 검증 명령으로 형식과 관계를 확인하세요.

## FAQ 삭제

FAQ를 없앨 때는 CSV 행을 지우지 않고 `baseline/deleted-faqs.json`(`{ "faqIds": ["99", "100"] }`)에 ID를 추가합니다. ID는 연속이어야 하고 `faq_log`·미응답 질문·`old_faq` 등이 FAQ ID를 참조하므로, 행을 지우거나 그 ID를 다른 FAQ로 재사용하지 않습니다. 초기화 스크립트가 이 목록의 FAQ를 소프트 삭제(`deleted_at`)합니다.

## 상품 정보 FAQ

`PRODUCT_DATA` FAQ는 의도 판정과 상품 조회 도구 선택에만 쓰이고, 답변은 채팅 프롬프트에 근거로 들어가지 않습니다(`USER_DATA`·`STORE_DATA`와 같습니다). 상품 가격·제공량·목록과 가입 대상·포함 혜택·약정 여부 같은 상품별 규칙은 채팅에서 상품 조회 도구가 DB의 상품 컬럼과 설명(`description`)에서 읽습니다. 그래서 상품별 규칙을 고칠 때는 FAQ가 아니라 상품 설명을 고칩니다. 답변에는 상품 가격·제공량·목록을 쓰지 않고, 관리자 화면에서 읽을 서술형 문장을 둡니다. 카테고리는 상품 조회 도구와 연결되는 `요금제`, `요금제/변경`, `부가서비스`, `결합상품`, `해외 로밍`, `로밍/통신망 선택` 중 하나여야 합니다.

## 검증

프로젝트 루트에서 `python -X utf8 .\tools\faq-db\validate_baseline_seed.py`로 두 CSV의 형식과 FAQ별 variant, `PRODUCT_DATA` 카테고리, `deleted-faqs.json`의 ID를 검사할 수 있습니다. 답변 문장은 `python -X utf8 .\tools\faq-db\check_sentence_gate.py --ids <FAQ ID>`로 검사합니다. `scripts/reset-local-db.ps1`과 `scripts/reset-faq-data.ps1`은 FAQ와 variant를 다시 적재하며, 어느 스크립트도 벡터를 생성하거나 적재하지 않습니다.
