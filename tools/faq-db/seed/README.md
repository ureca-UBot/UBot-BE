# FAQ 시드 데이터

`baseline-faqs-N.csv`와 `baseline-faq-variants-N.csv`가 시드의 기준 데이터입니다. 두 파일은 같은 번호로 짝지으며, 번호는 1부터 연속입니다. 한 쌍에는 FAQ를 최대 200건, variant를 최대 1,000건 넣습니다. 마지막 쌍은 이보다 적어도 됩니다. FAQ마다 원본 질문 1개와 유사표현 4개, 총 5개 variant를 저장합니다.

```text
baseline-faqs-N.csv: faqId,category,question,answer,intent
baseline-faq-variants-N.csv: originFaqId,variantId,question,vector
```

`vector`는 Ollama `bge-m3:567m`으로 `질문: {variant 질문}\n답변: {해당 FAQ 답변}`을 임베딩한 1,024차원 벡터입니다. 시드는 이를 `faq_embeddings`의 `QUESTION_ANSWER` 유형으로 저장합니다. 시드 실행 중에는 Ollama를 호출하지 않습니다.

`days/additional-faq-날짜.json`은 날짜별 추가 적재 대상 FAQ ID와 CSV 번호를 기록합니다. 해당 날짜의 TXT로 CSV를 생성하려면 `tools/faq-db/prepare_additional_faq.py`를, 기존 DB에 그날의 신규 건만 추가하려면 `tools/faq-db/import-additional-faq.ps1 날짜`를 사용합니다. 날짜별 목록을 보존해야 다른 DB에도 같은 추가분을 적재할 수 있습니다.

모든 번호의 파일 쌍을 검사하려면 프로젝트 루트에서 실행합니다.

```powershell
python -X utf8 .\tools\faq-db\validate_baseline_seed.py
```

`reset-local-db.ps1`, `reset-faq-data.ps1`, `reset-faq-tables.ps1`은 이 검증을 통과한 모든 번호의 CSV를 읽습니다. 처음 두 스크립트는 기존 데이터를 삭제하므로 실행 전에 [초기화 안내](../../../docs/how-to/reset-local-db.md)를 확인하세요. `reset-faq-tables.ps1`은 FAQ를 참조하는 이력·로그·미응답 기록이 있으면 중단합니다. 시드에는 기본 계정 `user@user.com`(USER), `admin@admin.com`(ADMIN)이 있으며 비밀번호는 모두 `12345678`입니다.

시드에는 `QUESTION_ANSWER` 벡터만 포함됩니다. 검색 설정이 `QUESTION`이거나 임베딩 Profile이 다르면 해당 벡터를 백필해야 합니다.
