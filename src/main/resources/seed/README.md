# 기본 FAQ 시드 데이터

`baseline-faqs.csv`는 로컬 기본 데이터베이스를 초기화할 때 사용하는 FAQ 기준 데이터입니다.
FAQ를 추가하거나 수정할 때도 이 CSV를 직접 편집합니다.
다음 다섯 컬럼을 유지해야 합니다.

```text
category,question,answer,intent,vector
```

`question`, `answer`, `category`, `intent`를 변경한 뒤에는 Ollama가 실행 중인 상태에서
벡터를 다시 생성합니다.

```powershell
python tools/generate_faq_vectors.py --model bge-m3:567m --input-columns question
```

벡터 생성 스크립트는 `baseline-faqs.csv`에 `vector` 컬럼을 추가하거나 기존 값을
갱신하고,
`baseline-faqs.metadata.json`에 임베딩 모델·차원·FAQ 수·CSV SHA-256을 기록합니다.
시드 작업은 이 벡터를 그대로 `faq_embeddings`에 Ollama Profile(메타데이터의 모델, 버전 1)로
저장하므로, DB 초기화 중에는 Ollama 임베딩 요청을 수행하지 않습니다. `AI_MODE=vllm`으로
실행할 때는 시드 뒤에 `tools/backfill-embeddings.ps1`로 vLLM Profile의 벡터를 채웁니다. 임베딩 대상 컬럼 또는 모델을 변경했다면 아래 명령으로
CSV의 벡터를 다시 생성합니다.

질문과 답변을 함께 임베딩하려면 입력 컬럼을 추가합니다. 다만 백엔드는 질문만 임베딩한 벡터로
검색하므로, 시드 스크립트는 `question`만으로 만든 벡터가 아니면 실행을 멈춥니다.

```powershell
python tools/generate_faq_vectors.py --model bge-m3:567m --input-columns question answer
```

로컬 데이터베이스 초기화와 시드는 프로젝트 루트에서 다음 명령으로 실행합니다.

```powershell
.\tools\reset-local-db.ps1
```

이 명령은 PostgreSQL 데이터 볼륨만 삭제합니다. Ollama 모델, Redis, Prometheus
볼륨은 유지하며, Flyway 마이그레이션 뒤 사용자 2명, FAQ 카테고리 17개, FAQ와
질문 벡터 1,000개를 저장합니다.

생성되는 로컬 기본 계정은 다음과 같습니다. 비밀번호는 모두 BCrypt로 해시하여
저장합니다.

| 이메일 | 비밀번호 | 역할 |
| --- | --- | --- |
| `user@user.com` | `12345678` | `USER` |
| `admin@admin.com` | `12345678` | `ADMIN` |
