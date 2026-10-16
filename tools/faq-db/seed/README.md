# FAQ 시드 데이터

`baseline-faqs.csv`와 `baseline-faq-variants.csv`가 시드의 기준 데이터입니다. FAQ마다 원본 질문 1개와 유사표현 4개, 총 5개 variant를 저장합니다.

```text
baseline-faqs.csv: faqId,category,question,answer,intent
baseline-faq-variants.csv: originFaqId,variantId,question
```

시드 CSV에는 임베딩 벡터가 없습니다. FAQ 검색에 필요한 `QUESTION` 벡터는 데이터를 적재한 뒤 생성해야 합니다.

추가 FAQ를 준비할 때는 두 CSV에 FAQ와 각 FAQ의 variant 5개를 추가하고, `days/additional-faq-날짜.json`에 해당 FAQ ID를 기록합니다. 파일 형식은 `{ "date": "1015", "faqIds": ["1001", "1002"] }`입니다. 이 목록을 보존해야 다른 DB에도 같은 추가분을 적재할 수 있습니다.

FAQ ID는 기존 마지막 ID 다음부터 연속으로, variant ID도 기존 마지막 ID 다음부터 연속으로 지정합니다. 각 FAQ의 첫 variant 질문은 FAQ 원본 질문과 같아야 합니다. CSV를 직접 수정한 뒤 검증 명령으로 형식과 관계를 확인하세요.

프로젝트 루트에서 `python -X utf8 .\tools\faq-db\validate_baseline_seed.py`로 두 CSV의 형식과 FAQ별 variant를 검사할 수 있습니다. `reset-local-db.ps1`과 `reset-faq-data.ps1`은 FAQ와 variant를 다시 적재하고, `import-additional-faq.ps1 날짜`는 날짜별 목록에 있는 FAQ와 variant만 추가합니다. 어느 스크립트도 벡터를 생성하거나 적재하지 않습니다.
