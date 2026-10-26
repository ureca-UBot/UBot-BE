# 매장 찾기에서 방문 예약까지

[전체 기능](../README.md) · [AI 상담](ai-chat.md) · [예약 알림](customer-support.md)

고객은 현재 위치·지역·지도에서 매장을 찾고, 이동 경로를 확인한 뒤 방문 시간을 예약합니다. 채팅의 매장 안내도 같은 매장 자료를 사용해 답변과 지도용 목록을 제공합니다.

## 찾기와 길찾기

1. 화면이 지역, 지도 경계, 현재 위치 또는 주소·장소 검색어를 보냅니다.
2. 서버는 주소·장소를 카카오 로컬 API로 찾고, 저장된 매장의 좌표를 PostGIS로 검색합니다. 지도 범위·확대 수준에 따라 개별 매장이나 묶음을 반환합니다.
3. 고객이 매장을 선택하면 상세 정보와 제공 서비스를 확인합니다. 관리자는 매장 등록·수정·활성화·삭제·복구로 이 정보를 관리합니다.
4. 출발 위치와 이동 수단을 보내면 도보·자동차·대중교통 경로를 조회합니다. 대중교통 후보를 고른 뒤에는 정류장까지의 도보 구간을 포함한 상세 경로를 요청할 수 있습니다.

채팅에서 장소를 지정하면 그 장소를 기준으로 매장을 찾습니다. 기준 위치를 정할 수 없으면 화면에 위치 필요 상태를 알리고, 고객이 현재 위치 사용을 선택해 좌표를 보내면 다시 조회합니다. 모델이 없는 매장·전화번호·경로를 만들어 제공하지 않습니다.

## 예약 확정과 취소

1. 화면이 선택한 매장과 날짜의 예약 슬롯을 조회합니다. 서버는 매장 영업시간·예약 가능 날짜·기존 예약으로 가능 여부를 계산합니다.
2. 로그인한 고객이 방문 목적과 시간을 보내면 서버가 슬롯과 본인의 기존 예약·활성 예약 수를 다시 검사합니다.
3. 사용자 잠금과 DB 제약으로 동시 요청을 확인하고 예약과 예약 완료 알림을 저장합니다. 먼저 조회한 슬롯이 비어 있었더라도 접수 시 이미 차 있으면 예약할 수 없습니다.
4. 고객은 본인의 미래 예약을 취소할 수 있습니다. 관리자는 예약을 조회하고 완료·미방문·취소 상태로 처리합니다.
5. 리마인더가 방문 시점에 맞는 알림을 만들고, 고객은 알림 목록에서 확인합니다.

현재 예약은 오늘부터 14일 이내, 일요일 제외, 영업시간에 맞는 한 시간 단위 슬롯입니다. 본인의 미래 활성 예약은 최대 3건입니다. 조건과 동시성 검사는 서버가 수행합니다.

## 화면과 서버의 연결

| 행동 | API | 결과 |
|---|---|---|
| 목록·지도·주변 검색 | GET `/stores`, `/stores/map`, `/stores/map/clusters`, `/stores/nearby` | 매장 목록 또는 지도 묶음 |
| 주소·장소 찾기 | GET `/locations/search` | 기준 위치 후보 |
| 이동 경로 찾기 | `/stores/{storeId}/directions`, `/directions/transit-detail` | 이동 수단별 경로·소요 시간 |
| 예약 시간 찾기 | GET `/stores/{storeId}/reservation-slots` | 시간별 예약 가능 여부 |
| 예약하기·내 예약 확인 | POST `/reservations`, GET `/reservations/me` | 확정 예약·본인 예약 목록 |
| 예약 취소 | PATCH `/reservations/{reservationId}/cancel` | 취소 상태와 알림 |

공개 매장·슬롯 조회와 로그인 예약 변경을 구분합니다. 외부 지도 API 실패는 경로 생성 실패로 알리고, 예약 충돌은 최신 슬롯을 다시 조회해 선택하도록 처리합니다.

## 관련 코드와 검증

- 매장 검색: [StoreService](../../src/main/java/com/ubot/store/service/StoreService.java), [공간 검색 SQL](../../src/main/resources/sql/store)
- 길찾기와 채팅: [DirectionsService](../../src/main/java/com/ubot/direction/service/DirectionsService.java), [StoreTools](../../src/main/java/com/ubot/ai/tool/StoreTools.java)
- 예약: [ReservationService](../../src/main/java/com/ubot/reservation/service/ReservationService.java), [ReservationReminderService](../../src/main/java/com/ubot/notification/reminder/ReservationReminderService.java)
- 회귀 테스트: [공간 저장소](../../src/test/java/com/ubot/store/repository/StoreRepositoryIntegrationTest.java), [예약 조건](../../src/test/java/com/ubot/reservation/service/ReservationServiceTest.java), [알림 중복 방지](../../src/test/java/com/ubot/notification/reminder/NotificationReminderIntegrationTest.java)
