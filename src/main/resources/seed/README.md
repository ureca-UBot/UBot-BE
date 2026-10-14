# 기본 FAQ 시드 데이터

`baseline-faqs.csv`는 FAQ 1,000건의 기준 데이터입니다. `faqId`는 1부터 1,000까지이며, FAQ 자체에는 벡터를 저장하지 않습니다. `baseline-faqs-1.csv`부터 `baseline-faqs-5.csv`까지는 이를 200건씩 나눈 파일입니다. 각 파일의 컬럼은 다음과 같습니다.

```text
faqId,category,question,answer,intent
```

`baseline-faq-variants-1.csv`부터 `baseline-faq-variants-5.csv`까지는 각 FAQ의 질문 5개(원문 1개와 직접 작성한 유사표현 4개)를 저장합니다. 첫 번째 variant는 반드시 FAQ의 원문과 같습니다. `variantId`는 전체 파일에서 1부터 5,000까지 연속입니다.

```text
originFaqId,variantId,question,vector
```

`vector`는 Ollama `bge-m3:567m`으로 `질문: {variant 질문}\n답변: {원본 FAQ 답변}`을 임베딩한 1,024차원 벡터입니다. 시드에서는 이를 `faq_embeddings`의 `QUESTION_ANSWER` 유형으로 저장합니다. FAQ 파일에는 벡터가 없고, 시드 실행 중에는 Ollama를 호출하지 않습니다.

로컬 DB를 새로 만들고 모든 데이터를 입력하려면 프로젝트 루트에서 실행합니다. **PostgreSQL 데이터 볼륨이 삭제됩니다.** 실행 전에 10개 CSV를 모두 검증하며, 검증에 실패하면 볼륨을 삭제하지 않습니다.

```powershell
.\tools\reset-local-db.ps1
```

스크립트는 Flyway 적용 뒤 기본 사용자 2명, 카테고리 17개, FAQ 1,000개, variant 5,000개, `QUESTION_ANSWER` 벡터 5,000개를 넣고 건수와 차원을 확인합니다. 기본 계정은 `user@user.com`(USER), `admin@admin.com`(ADMIN)이며, 비밀번호는 모두 `12345678`입니다.

기존 DB에서 FAQ 관련 데이터만 교체하려면 `.\tools\reset-faq-data.ps1`을 실행합니다. 이 명령은 기존 FAQ와 이력, FAQ 로그를 지우고 새 시드를 넣습니다. 기본 ADMIN 계정이 있어야 하며 다른 사용자 데이터와 PostgreSQL 볼륨은 유지합니다.

**`faq`, `faq_question_variants`, `faq_embeddings` 세 테이블만** 교체하려면 `.\tools\reset-faq-tables.ps1`을 실행합니다. 기존 카테고리, ADMIN 계정, Ollama 임베딩 프로필이 있어야 합니다. FAQ 이력·로그·미응답 기록이 FAQ를 참조 중이면 다른 테이블을 건드리지 않도록 작업을 중단합니다. CSV 검증과 삽입은 트랜잭션으로 처리합니다.

CSV 내용이 바뀌면 FAQ 분할 파일과 해당 variant의 문장 및 벡터를 함께 갱신해야 합니다. `tools/build_baseline_faq_variants.py --part N`은 `variant-drafts-N.txt`에 직접 작성한 문장을 읽고 해당 1,000개 벡터를 다시 생성합니다. 생성 중 중단되면 `.partial.csv`부터 이어서 실행할 수 있습니다. 입력 문장에 맞는 Ollama 임베딩 서버가 필요합니다.
