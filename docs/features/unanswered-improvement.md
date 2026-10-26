# 미응답 질문에서 FAQ 개선까지

[전체 기능](../README.md) · [FAQ 관리와 검색 반영](faq-management.md) · [AI 상담](ai-chat.md)

고객이 답을 받지 못한 질문을 관리자가 확인하고, 반복되는 문의부터 FAQ로 보강합니다. 질문을 모으는 것에서 끝내지 않고 수정한 FAQ가 이전 미응답을 검색으로 찾을 수 있는지도 확인합니다.

## 수집에서 개선까지

1. AI 상담이 검색 결과 없음, 유사도·문장 근거 부족 또는 수집 대상인 모델 답변 보류를 발견합니다. 여러 질문을 함께 물었다면 답하지 못한 하위 질문을 따로 기록합니다.
2. 답변 처리 결과와 함께 DB Outbox에 저장할 후보를 남깁니다. 별도 소비자가 후속 임베딩·그룹화를 처리하므로, 후속 처리 실패를 고객 응답 완료와 분리할 수 있습니다.
3. 원문 조각과 검색어를 함께 저장하고 현재 Profile의 벡터로 가까운 미응답 묶음을 찾습니다. 알맞은 묶음이 없으면 새로 만들고, 질문 수와 중심 벡터를 갱신합니다.
4. 같은 시도·하위 질문 또는 같은 질문의 반복 재시도가 묶음 건수를 늘리지 않도록 확인합니다. 검색 때 만든 벡터를 같은 질문·Profile에서 재사용할 수 있습니다.
5. 관리자는 최근순·건수순 목록과 상세 질문·사유·연관 FAQ를 보고 승인·보류·반려를 선택합니다. 이름 붙이기 작업은 주제와 추천 카테고리를 보조 정보로 제공합니다.
6. 묶음에서 FAQ를 만들면 FAQ 등록과 묶음의 해결 상태를 함께 저장합니다. 이미 해결한 묶음에서 중복 생성하거나 상태를 다시 바꾸는 요청은 거절합니다.
7. FAQ 변경 이벤트는 미응답 재검증으로 연결됩니다. 현재 질문 벡터와 FAQ 검색 기준의 대응 결과를 관리 화면에 표시합니다.

재검증은 FAQ와의 검색 대응을 확인하는 기능입니다. 전체 답변을 실제 모델로 다시 생성해 모든 문장이 정확하다고 판정하는 평가와는 다릅니다.

## 관리자 화면의 요청과 결과

| 행동 | 요청 | 결과 |
|---|---|---|
| 개선할 질문 찾기 | GET `/admin/unanswered-groups` | 상태·최소 건수·정렬·페이지 조건에 맞는 묶음과 재검증 요약 |
| 원인 살펴보기 | GET `/admin/unanswered-groups/{groupId}` | 개별 질문, 원인, 관련 FAQ와 재검증 결과 |
| 검토 상태 변경 | PATCH `/admin/unanswered-groups/{groupId}/status` | 변경한 처리 상태 |
| FAQ로 전환 | POST `/admin/unanswered-groups/{groupId}/faq` | 생성 FAQ가 연결된 해결 묶음 |
| 임베딩 엔진 변경 후 복구 | POST `/admin/unanswered-groups/embeddings/backfill` | 현재 Profile에 채운 벡터 수 |

## 저장·실패 처리

질문과 묶음은 Profile을 바꿔도 유지하고, 질문 벡터와 묶음 중심은 Profile별로 저장합니다. 다른 Profile의 벡터를 서로 비교하지 않으므로 엔진 변경 뒤에는 백필이 필요합니다.

Outbox는 후속 작업의 재시도 지점을 남깁니다. 임베딩 장애를 정상 저장으로 처리하지 않으며, 복구 후 다시 소비할 수 있습니다. 입력 차단·DB 장애·모델 접속 실패 등 시스템 오류는 지식 부족과 구분합니다. 출력 가드가 만든 보류도 모델 자체의 미응답과 구분합니다.

FAQ 전환은 관리자 입력 검증과 임베딩 생성이 성공해야 완료됩니다. 화면은 성공 결과를 받은 뒤 목록을 새로 조회하고, 실패 시 원래 묶음을 해결된 것으로 표시하지 않아야 합니다.

## 관련 코드와 검증

- 수집·그룹화: [UnansweredQuestionService](../../src/main/java/com/ubot/unanswered/service/UnansweredQuestionService.java), [UnansweredOutboxHandler](../../src/main/java/com/ubot/unanswered/service/UnansweredOutboxHandler.java)
- 관리자 처리: [UnansweredGroupService](../../src/main/java/com/ubot/unanswered/service/UnansweredGroupService.java)
- 변경 후 재검증: [UnansweredReverifyHandler](../../src/main/java/com/ubot/unanswered/service/UnansweredReverifyHandler.java), [UnansweredReverifyRepository](../../src/main/java/com/ubot/unanswered/repository/UnansweredReverifyRepository.java)
- 회귀 테스트: [Outbox 처리](../../src/test/java/com/ubot/unanswered/service/UnansweredOutboxHandlerTest.java), [FAQ 전환](../../src/test/java/com/ubot/unanswered/service/UnansweredGroupFaqTest.java), [재검증](../../src/test/java/com/ubot/unanswered/service/UnansweredReverifyTest.java)
- 설계 근거: [미응답 저장 Outbox ADR](../미응답_저장_Outbox_ADR.md)
