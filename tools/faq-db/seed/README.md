# FAQ 시드 데이터

`baseline-faqs.csv`와 `baseline-faq-variants.csv`가 시드의 기준 데이터입니다. FAQ마다 원본 질문을 첫 variant로 저장하고, 유사표현은 필요한 만큼 추가할 수 있습니다.

```text
baseline-faqs.csv: faqId,category,question,answer,intent
baseline-faq-variants.csv: originFaqId,variantId,question
```

시드 CSV에는 임베딩 벡터가 없습니다. FAQ 검색에 필요한 `QUESTION` 벡터는 데이터를 적재한 뒤 생성해야 합니다.

추가 FAQ를 준비할 때는 두 CSV에 FAQ와 각 FAQ의 원본 질문 및 유사표현 variant를 추가하고, `days/additional-faq-날짜.json`에 해당 FAQ ID를 기록합니다. 파일 형식은 `{ "date": "1015", "faqIds": ["1001", "1002"] }`입니다. 이 목록을 보존해야 다른 DB에도 같은 추가분을 적재할 수 있습니다.

FAQ ID는 기존 마지막 ID 다음부터 연속으로, variant ID도 기존 마지막 ID 다음부터 연속으로 지정합니다. 각 FAQ의 첫 variant 질문은 FAQ 원본 질문과 같아야 합니다. CSV를 직접 수정한 뒤 검증 명령으로 형식과 관계를 확인하세요.

## 기존 FAQ 수정

이미 적재된 FAQ의 카테고리·질문·답변·의도나 variant 문장을 고칠 때는 두 CSV의 같은 행을 수정하고, `days/updated-faq-날짜.json`에 수정한 FAQ ID와 수정 전 값을 기록합니다. FAQ ID와 variant ID는 바꾸지 않습니다. 질문을 바꾸면 그 FAQ의 첫 variant도 같은 문장으로 바꿉니다.

```json
{
  "date": "1022",
  "faqIds": ["113"],
  "before": [
    {
      "faqId": "113", "category": "요금제", "question": "수정 전 질문", "answer": "수정 전 답변", "intent": "GENERAL",
      "variants": [{ "variantId": "561", "question": "수정 전 variant 문장" }]
    }
  ]
}
```

`before`에는 FAQ마다 수정 전 카테고리·질문·답변·의도를 모두 적고, `variants`에는 문장을 바꾼 variant만 적습니다. 수정 적재 스크립트는 DB 값이 `before`와 같은 FAQ만 고치므로 관리자가 따로 고친 FAQ를 덮어쓰지 않습니다.

## FAQ 삭제

FAQ를 없앨 때는 CSV 행을 지우지 않고 `deleted-faqs.json`(`{ "faqIds": ["99", "100"] }`)에 ID를 추가합니다. ID는 연속이어야 하고 `faq_log`·미응답 질문·`old_faq` 등이 FAQ ID를 참조하므로, 행을 지우거나 그 ID를 다른 FAQ로 재사용하지 않습니다. 초기화 스크립트와 수정 적재 스크립트가 이 목록의 FAQ를 소프트 삭제(`deleted_at`)합니다.

## 상품 정보 FAQ

`PRODUCT_DATA` FAQ는 의도 판정과 상품 조회 도구 선택에만 쓰이고, 답변은 채팅 프롬프트에 근거로 들어가지 않습니다(`USER_DATA`·`STORE_DATA`와 같습니다). 상품 가격·제공량·목록과 가입 대상·포함 혜택·약정 여부 같은 상품별 규칙은 채팅에서 상품 조회 도구가 DB의 상품 컬럼과 설명(`description`)에서 읽습니다. 그래서 상품별 규칙을 고칠 때는 FAQ가 아니라 상품 설명을 고칩니다. 답변에는 상품 가격·제공량·목록을 쓰지 않고, 관리자 화면에서 읽을 서술형 문장을 둡니다. 카테고리는 상품 조회 도구와 연결되는 `요금제`, `요금제/변경`, `부가서비스`, `결합상품`, `해외 로밍`, `로밍/통신망 선택` 중 하나여야 합니다.

## 검증

프로젝트 루트에서 `python -X utf8 .\tools\faq-db\validate_baseline_seed.py`로 두 CSV의 형식과 FAQ별 variant, `PRODUCT_DATA` 카테고리, `deleted-faqs.json`의 ID를 검사할 수 있습니다. 답변을 고친 FAQ는 `python -X utf8 .\tools\faq-db\check_sentence_gate.py --date 날짜`로 variant마다 답변 문장 검사(기준 0.6)를 통과하는 문장이 있는지 확인합니다. `reset-local-db.ps1`과 `reset-faq-data.ps1`은 FAQ와 variant를 다시 적재하고, `import-additional-faq.ps1 날짜`는 날짜별 목록에 있는 FAQ와 variant만 추가하며, `update-existing-faq.ps1 날짜`는 수정 목록과 삭제 목록을 기존 DB에 반영합니다. 어느 스크립트도 벡터를 생성하거나 적재하지 않습니다.
