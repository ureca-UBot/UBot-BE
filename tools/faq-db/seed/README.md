# FAQ 시드 데이터

`baseline-faqs.csv`와 `baseline-faq-variants.csv`가 시드의 기준 데이터입니다. FAQ마다 원본 질문 1개와 유사표현 4개, 총 5개 variant를 저장합니다.

```text
baseline-faqs.csv: faqId,category,question,answer,intent
baseline-faq-variants.csv: originFaqId,variantId,question
```

시드 CSV에는 임베딩 벡터가 없습니다. FAQ 검색에 필요한 `QUESTION` 벡터는 데이터를 적재한 뒤 생성해야 합니다.

`days/additional-faq-날짜.json`은 날짜별 추가 적재 대상 FAQ ID를 기록합니다. 날짜별 목록을 보존해야 다른 DB에도 같은 추가분을 적재할 수 있습니다.

현재 `tools/faq-db`의 적재·검증 스크립트는 번호가 붙은 CSV와 variant의 `vector` 열을 사용하도록 작성되어 있습니다. 새 시드 형식을 사용하려면 해당 스크립트를 함께 수정해야 합니다.
