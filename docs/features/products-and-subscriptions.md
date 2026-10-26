# 상품 안내와 내 이용 정보

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [알림](customer-support.md)

고객은 상품 목록에서 조건을 비교하고, 로그인 후 본인 회선의 가입 상품·약정·청구·사용량을 확인합니다. 채팅에서도 같은 조회 서비스를 사용해 확인한 자료로 답변과 카드를 만듭니다.

## 상품을 찾아 가입·변경하기

1. 비회원과 회원 모두 요금제·부가서비스·로밍·결합·홈상품 카탈로그를 조회합니다. 관리자는 별도 관리자 API에서 상품 정보를 관리합니다.
2. 가입이나 변경을 선택하면 로그인 사용자의 요청으로 처리합니다. 서버는 회선 소유권과 상품의 활성 상태·가입 조건을 검사합니다.
3. 신규 회선 생성, 요금제 변경, 부가서비스 가입·취소, 로밍 일정 등록·취소, 결합 구성, 홈상품 가입·해지 결과를 DB에 저장합니다.
4. 화면은 확정된 응답을 받고 본인 가입 내역을 다시 조회합니다. 회선 해지 때는 연결된 부가서비스·로밍·결합 상태도 정리합니다.

이 저장소에서 관리하는 가입 상태이며, 실제 통신사 원장에 회선을 개통하거나 결제를 승인하는 외부 연동을 뜻하지 않습니다.

## 조회와 변경의 경계

| 목적 | API 영역 | 반환·저장 내용 |
|---|---|---|
| 상품 탐색 | `/plans`, `/addon-services`, `/roaming-products`, `/bundle-products`, `/home-products` | 상품 조건·요금·제공량 |
| 내 회선·상품 관리 | `/subscriptions/me` | 회선, 요금제, 부가서비스, 로밍, 결합 가입 상태 |
| 홈상품 가입 관리 | `/home-subscriptions/me` | 인터넷·IPTV 가입 내역 |
| 청구·납부 확인 | `/subscriptions/me/{subscriptionId}/billing-records` | 월별 청구 금액·항목·납부 상태 |
| 사용량 확인 | `/subscriptions/me/{subscriptionId}/usage-records` | 월별 데이터·음성·문자 사용·잔여량 |
| 약정 확인 | `/subscriptions/me/{subscriptionId}/contract`, `/contracts` | 현재 약정과 약정 이력 |

개인 자료는 로그인 사용자와 회선 소유권을 모두 확인합니다. 잘못된 회선이나 다른 사용자의 식별자로 범위를 넓힐 수 없습니다. 변경 조건이 맞지 않으면 오류를 반환하며 화면이 임의로 성공 상태를 확정하면 안 됩니다.

## 채팅에서 같은 자료를 쓰는 흐름

1. FAQ 검색 결과가 개인 이용 정보나 상품 조회를 요구하면 서버가 관련 자료 수집을 선택합니다.
2. 본인 회선의 최근 자료는 서버가 요약하고, 이전 월의 사용량·청구서처럼 추가 조회가 필요하면 허용된 도구를 제공합니다.
3. 사용자 ID는 모델 인자가 아니라 서버의 ToolContext로 전달합니다. 도구가 호출하는 서비스에서도 회선 소유권을 다시 확인합니다.
4. 회선이 여러 개라 하나를 정할 수 없으면 선택 안내를 반환합니다. 없는 월·미래 월·조회 실패를 실제 사용량이나 청구 금액으로 추측하지 않습니다.
5. 상품은 질문에 필요한 종류의 검색 도구를 연결합니다. 조회한 데이터로 답변 근거와 화면 카드를 만들고 수치 검사를 적용합니다.

채팅 도구는 조회를 담당합니다. 모델이 가입이나 변경을 제안하더라도 실제 상태 변경은 고객의 명시적인 API 요청과 서버 검증을 거칩니다.

## 관련 코드와 검증

- 가입·변경: [SubscriptionCommandService](../../src/main/java/com/ubot/subscription/service/SubscriptionCommandService.java), [BundleGroupService](../../src/main/java/com/ubot/subscription/service/BundleGroupService.java)
- 소유권: [SubscriptionAccessService](../../src/main/java/com/ubot/subscription/service/SubscriptionAccessService.java)
- 채팅 자료: [UserDataSummarizer](../../src/main/java/com/ubot/ai/tool/UserDataSummarizer.java), [UserDataTools](../../src/main/java/com/ubot/ai/tool/UserDataTools.java), [ProductIntentHandler](../../src/main/java/com/ubot/chat/context/ProductIntentHandler.java)
- 조회 도구 등록: [AiToolRegistry](../../src/main/java/com/ubot/ai/tool/AiToolRegistry.java)
- 회귀 테스트: [가입 동작](../../src/test/java/com/ubot/subscription/ProductJoinIntegrationTest.java), [소유권·상품 보호](../../src/test/java/com/ubot/product/ProductSubscriptionProtectionIntegrationTest.java), [결합 동시성](../../src/test/java/com/ubot/subscription/BundleGroupConcurrencyTest.java), [이용 정보 도구](../../src/test/java/com/ubot/ai/tool/UserDataToolsTest.java)
