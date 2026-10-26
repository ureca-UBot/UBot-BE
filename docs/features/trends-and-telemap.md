# 인기 FAQ와 지역 문의 급증 감지

[전체 기능](../README.md) · [FAQ 개선](faq-management.md) · [공지·알림](customer-support.md)

고객에게는 많이 찾는 FAQ를 보여 주고, 관리자에게는 특정 지역에서 유사 문의가 갑자기 늘어나는 신호를 제공합니다. FAQ 사용 순위를 집계하는 기능과 질문 의미를 묶어 급증을 찾는 기능은 서로 다른 자료를 만듭니다.

## 인기·급상승 FAQ

1. 답변에 사용한 FAQ와 회원 질문의 시간·지역 정보를 기록합니다. 게스트 질문은 랭킹 집계 대상에서 제외합니다.
2. 스케줄러가 정해진 기간의 사용 기록을 읽어 인기 FAQ, 전체 급상승 FAQ, 지역별 급상승 FAQ를 계산합니다.
3. 인기 순위는 사용량을, 급상승 순위는 기준 기간 대비 증가와 최소 표본 조건을 봅니다. 지역별 정책이 있으면 해당 지역의 조건을 적용합니다.
4. 결과와 계산 시각을 Redis 스냅샷으로 저장합니다. 고객 화면은 공개 GET `/rankings`로 읽습니다.

회원의 기록을 집계한다는 것과 조회에 로그인이 필요하다는 것은 다릅니다. 조회 API는 공개입니다. 아직 집계하지 않은 null과 집계 결과가 없는 빈 목록 모두 화면에서 빈 상태로 처리할 수 있지만 서버 상태는 구분됩니다.

## 지역 문의 급증 감지

1. 저장된 질문 로그에서 지역과 회원 정보가 있는 질문을 Outbox 후속 작업으로 전달합니다.
2. 하위 질문 또는 원문을 현재 Profile로 임베딩하고 같은 지역·Profile의 비슷한 질문 묶음에 넣습니다.
3. 같은 시간 구간에서 한 사용자가 같은 묶음에 반복 질문해도 참여 건수를 중복해서 늘리지 않습니다. 지역·Profile 잠금과 저장 검증으로 동시 등록을 보호합니다.
4. 시간 구간의 건수가 기준을 넘으면 알림을 선점하고 당시 질문 사본과 건수를 저장합니다.
5. 별도 작업이 LLM으로 주제 이름을 만들고 관리자 알림을 생성합니다. 이름 생성이 실패하면 지역 기반 기본 제목으로 알림을 이어갑니다.
6. 관리자는 알림 상세를 확인하고 처리 완료로 표시합니다. 실제 장애인지 판단하고 공지를 작성하는 업무는 관리자가 수행합니다.

## 관리자 화면과 저장 결과

| 행동 | API | 결과 |
|---|---|---|
| 순위 확인 | GET `/rankings` | 인기·전체 급상승·지역별 급상승 스냅샷과 계산 시각 |
| 문의 급증 목록 | GET `/admin/telemap/alerts` | 상태·지역별 알림 목록 |
| 원인 확인 | GET `/admin/telemap/alerts/{alertId}` | 감지 시점의 질문 사본·건수·기간·주제 |
| 대응 완료 표시 | PATCH `/admin/telemap/alerts/{alertId}/processed` | 처리 상태와 담당 관리자 |

순위 스냅샷은 Redis에, 급증 감지의 묶음·질문·벡터·알림 사본은 DB에 저장합니다. Redis 조회 실패를 정상적인 빈 집계로 단정하지 않아야 합니다. 급증 감지는 저장된 질문 자료에 기반하므로 통신망 계측이나 모든 고객 불만을 직접 관측하는 기능은 아닙니다.

## 관련 코드와 검증

- 순위 집계·조회: [RankingService](../../src/main/java/com/ubot/ranking/service/RankingService.java), [RankingQueryRepository](../../src/main/java/com/ubot/ranking/repository/RankingQueryRepository.java)
- 질문 묶기·알림 확정: [TelemapSurgingQuestionService](../../src/main/java/com/ubot/telemap/service/TelemapSurgingQuestionService.java), [TelemapSurgingQuestionPersistenceService](../../src/main/java/com/ubot/telemap/service/TelemapSurgingQuestionPersistenceService.java)
- 주제·알림 연결: [TelemapAlertLabelService](../../src/main/java/com/ubot/telemap/service/TelemapAlertLabelService.java)
- 회귀 테스트: [순위 집계](../../src/test/java/com/ubot/ranking/repository/RankingQueryRepositoryIntegrationTest.java), [급증 동시성](../../src/test/java/com/ubot/telemap/repository/TelemapConcurrencyIntegrationTest.java), [제목 생성 실패 처리](../../src/test/java/com/ubot/telemap/service/TelemapAlertLabelServiceTest.java)
