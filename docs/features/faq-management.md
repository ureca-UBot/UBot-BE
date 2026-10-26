# FAQ 관리와 검색 반영

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [미응답 개선](unanswered-improvement.md)

관리자는 공식 질문·답변과 여러 질문 표현을 관리합니다. 서버는 질문 표현을 검색 가능한 벡터로 만들고, 수정 이력을 보존하면서 현재 FAQ 버전이 답변에 쓰이도록 연결합니다.

## 등록·수정·삭제 흐름

1. 관리자가 카테고리, 질문, 공식 답변, 처리 의도, 유사 질문 표현과 공식 답변 직접 반환 허용 여부를 보냅니다.
2. 서버가 관리자·카테고리를 확인하고 원본 FAQ와 질문 표현을 저장합니다. 같은 FAQ 안에서 공백 차이로 중복되는 표현은 정리합니다.
3. 각 질문 표현을 현재 임베딩 엔진으로 벡터화하고 Profile·FAQ 버전과 함께 저장합니다. FAQ 등록 중 임베딩이 실패하면 해당 트랜잭션을 롤백합니다.
4. 수정할 때는 이전 FAQ를 이력으로 보관하고 버전을 올립니다. 원본 질문이 바뀌면 표현을 새로 만들고, 원본이 같으면 추가·삭제한 표현만 반영합니다. 유지한 벡터는 현재 Profile의 FAQ 버전을 맞춥니다.
5. 삭제는 논리 삭제로 처리해 검색에서 제외합니다. 복구는 기존 FAQ를 되살리고 필요한 원본 질문 표현을 준비합니다.
6. 변경 이벤트로 미응답 재검증을 예약합니다. 어떤 질문에 어떤 FAQ를 근거로 사용했는지는 사용 기록에서 확인합니다.

## 검색과 공식 답변 직접 반환

검색은 현재 Profile·FAQ 버전의 질문 벡터를 사용합니다. HNSW로 가까운 질문 표현을 찾고 같은 FAQ는 하나의 후보로 모은 뒤, 질문 유사도 기준을 적용합니다. 일반 FAQ는 답변 문장도 검사해 질문과 관련 있는 문장만 생성 모델의 근거로 보냅니다.

공식 답변 직접 반환은 관리자가 허용한 일반 FAQ에만 적용합니다. 원문 검색의 1위 FAQ가 기준 유사도와 2위와의 점수 차이를 모두 만족해야 합니다. 허용된 FAQ만 미리 추려 순위를 왜곡하지 않으며, 후보가 부족하면 생성 경로로 넘어갑니다. 개인·상품·위치 자료가 필요한 경로와 분리·멀티턴 질문에는 그대로 답변을 재사용하지 않습니다.

이 기능은 이전 LLM 답변을 저장해 돌려주는 응답 캐시와 다릅니다. 검색 시점의 공식 FAQ를 반환하며, 임베딩·검색은 수행하고 답변 생성 호출을 생략합니다.

## 관리자 화면과 데이터

| 업무 | API 영역 | 확인할 결과 |
|---|---|---|
| FAQ 등록·검색·수정·삭제 | `/admin/faqs` | 최신 질문·답변·의도·질문 표현·버전 |
| 삭제 목록·복원 | `/admin/deleted-faqs`, `/admin/faqs/restore` | 삭제 상태와 복원된 FAQ |
| 분류 관리 | `/admin/faq-categories` | 활성 카테고리 |
| 변경 이력·사용 기록 | `/admin/old-faqs`, `/admin/faq-logs` | 이전 답변과 실제 근거 사용 기록 |
| 검색 품질 개선 | `/admin/faq-monitoring` | 사용 현황, 중복 의심·낮은 검색 품질 후보, 개선 제외 이력 |
| AI 검토 보조 | `/admin/faq-monitoring/improvements/{faqId}/ai-review/**` | 중복 의심 또는 낮은 검색 품질에 대한 검토 결과 |
| 현재 엔진 벡터 채우기 | POST `/admin/faqs/embeddings/backfill` | 현재 Profile에서 누락되거나 오래된 벡터 보완 |

AI 검토 결과는 관리자 판단을 돕는 자료입니다. 검토 요청만으로 FAQ 원문을 자동 수정하지 않습니다. 저장 데이터는 FAQ 원문, 이전 버전, 질문 표현, Profile별 벡터와 질문별 사용 기록으로 나뉩니다.

## 엔진을 바꿀 때

임베딩 엔진·모델·차원·버전은 Embedding Profile을 구분하는 기준입니다. 다른 Profile로 바꾸면 해당 Profile의 벡터가 있어야 검색할 수 있습니다. 이전 벡터가 DB에 남아 있다는 사실만으로 새 엔진 검색이 준비된 것은 아닙니다. [모델 연결과 백필](../how-to/llm-module.md#백필), [로컬 데이터 준비](../how-to/local-data.md)를 함께 확인합니다.

## 임베딩 실험과 BE 반영

[UBot-EmbeddingTest](https://github.com/ureca-UBot/UBot-EmbeddingTest)는 모델 5종, 리랭커, 의도별 유사도 기준, 질문 묶기와 서빙 엔진을 비교한 기록입니다. 아래는 실험 결과와 현재 BE 구현의 대응입니다.

| 실험에서 확인한 내용 | BE에 반영한 구성 |
|---|---|
| 정답 라벨을 보정한 235문항에서 BGE-M3의 Top-1 94.5%, MRR 0.963 | BGE-M3 1,024차원 벡터 사용. Ollama는 `bge-m3:567m`, vLLM은 `BAAI/bge-m3`로 설정 ([설정](../../.env.example), [임베딩 클라이언트](../../src/main/java/com/ubot/embedding/client/EmbeddingClient.java)) |
| 같은 235문항에서 BGE 리랭커 추가 시 Top-1 91.9%, MRR 0.954 | FAQ 질문 표현의 `QUESTION` 벡터로 검색하고 별도 리랭커 없이 후보 순서 유지 ([검색 서비스](../../src/main/java/com/ubot/faq/retrieval/FaqRetrievalService.java)) |
| 의도별 실험에서 기준 후보 0.605·0.730의 재현율·무관 질문 배제율이 달랐음 | Top-K 기본값 3, 질문 유사도 기준 기본값 0.79 사용. 과거 실험 후보와 현재 설정을 구분 ([설정](../../src/main/resources/application.yml), [답변 처리](../../src/main/java/com/ubot/chat/service/ChatAnswerProcessor.java)) |
| 단기 HTTP 부하 2,560건에서 vLLM 처리량이 높았지만 모델 형식·정밀도는 달랐음 | `AI_MODE=vllm`과 OpenAI 호환 임베딩 API 연결 지원. 기본 모드는 Ollama이며 엔진별 벡터는 프로필로 분리 ([연결 설정](../../src/main/java/com/ubot/embedding/config/EmbeddingConfig.java), [프로필 관리](../../src/main/java/com/ubot/embedding/service/EmbeddingProfileService.java)) |
| HDBSCAN은 질문 38개로 동작만 확인했고 운영 파라미터 확정은 보류 | HDBSCAN은 미적용. 미응답 질문을 현재 프로필의 가까운 묶음에 넣고 중심 벡터를 평균으로 갱신 ([미응답 처리](../../src/main/java/com/ubot/unanswered/service/UnansweredQuestionService.java)) |

FAQ의 HNSW 검색 설정은 후속 [BE 검색 실험](../FAQ_HNSW_검색_테스트_보고서.md)을 거쳐 `ef_search=200`, `iterative_scan=strict_order`로 적용했습니다. 이전 [Q·Q+A와 전체 비교 실험](../FAQ_검색_테스트_보고서.md)의 결과도 함께 보존합니다. 엔진을 바꿀 때는 [현재 프로필의 벡터 백필](../how-to/llm-module.md#백필)이 필요합니다.

## 관련 코드와 검증

- 등록·버전 관리: [FaqService](../../src/main/java/com/ubot/faq/service/FaqService.java)
- 검색 저장소: [FaqVectorRepository](../../src/main/java/com/ubot/faq/repository/FaqVectorRepository.java)
- 공식 답변 승인 조건: [FaqSemanticCacheService](../../src/main/java/com/ubot/faq/cache/FaqSemanticCacheService.java)
- 관리자 개선 조회: [FaqMonitoringController](../../src/main/java/com/ubot/monitoring/faqmonitoring/controller/FaqMonitoringController.java)
- 회귀 테스트: [FAQ 서비스](../../src/test/java/com/ubot/faq/service/FaqServiceTest.java), [공식 답변 승인](../../src/test/java/com/ubot/faq/cache/FaqSemanticCacheServiceTest.java)
- 측정 기록: [임계값](../FAQ_Threshold_테스트_보고서.md), [리랭커](../FAQ_Reranker_테스트_보고서.md). 각 결과는 측정 당시 데이터·모델·설정의 범위에서 해석합니다.
