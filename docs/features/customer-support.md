# 공지·문의·알림

[전체 기능](../README.md) · [상품과 이용 정보](products-and-subscriptions.md) · [방문 예약](stores-and-reservations.md)

고객은 서비스 공지와 지역 장애 안내를 확인하고, 일대일 문의를 남기며, 예약·납부·약정·로밍·사용량 관련 알림을 확인합니다. 관리자는 공지와 문의 답변을 관리합니다.

## 공지와 지역 장애 안내

관리자가 공지 유형·대상 지역·노출 기간을 등록하면 고객 화면은 공개 API로 배너와 목록을 조회합니다. 배너는 현재 노출 기간과 접속 지역을 적용하고, 목록은 노출이 끝난 공지도 이력으로 포함합니다. 삭제한 공지는 조회에서 제외합니다.

접속 지역을 판별하지 못하면 배너에는 전체 대상 공지만 제공합니다. 장애 안내는 관리자가 등록한 정보이며, [지역 문의 급증 감지](trends-and-telemap.md)가 실제 통신 장애를 자동 확정하거나 공지를 자동 발행하는 기능은 아닙니다.

## 일대일 문의 처리

1. 로그인한 고객이 제목과 내용을 보내면 RECEIVED 상태로 저장합니다.
2. 고객은 본인 문의 목록을 읽고, 아직 답변하지 않은 문의를 수정·삭제할 수 있습니다.
3. 관리자는 상태별 목록과 상세를 보고 답변을 등록합니다. 답변과 처리 상태를 함께 저장합니다.
4. 이미 처리한 문의의 중복 답변·수정·삭제는 상태 검증으로 거절합니다. 수정과 답변이 동시에 들어오면 잠금으로 상태 변경을 보호합니다.

문의 답변은 문의 조회에서 확인합니다. 답변 등록이 별도 이메일·SMS 전송까지 포함한다는 의미는 아닙니다.

## 알림 생성과 읽기

예약 확정·취소는 업무 처리 중 알림을 만들고, 예약 방문·납부 기한·약정 만료·로밍 시작과 종료·데이터 사용량은 리마인더가 대상과 단계를 검사합니다. 같은 대상의 같은 단계 알림을 반복 생성하지 않도록 중복 키를 사용합니다.

고객 화면은 본인 알림 목록과 미읽음 개수를 조회하고 개별·전체 읽음 처리를 요청합니다. 다른 사용자의 알림은 읽음 처리할 수 없습니다. 관리자용 위험 표현·지역 급증 알림도 같은 알림 기능을 사용하지만 대상 사용자를 구분합니다.

## 화면과 서버의 연결

| 행동 | API | 결과 |
|---|---|---|
| 공지·장애 배너 보기 | GET `/notices/banners` | 지역과 현재 노출할 공지·장애 배너 |
| 공지 이력 읽기 | GET `/notices`, `/notices/{noticeId}` | 목록과 상세 |
| 문의 접수·본인 목록 | POST·GET `/inquiries` | 접수 내용과 답변 상태 |
| 문의 수정·삭제 | PATCH·DELETE `/inquiries/{inquiryId}` | 미처리 본인 문의 변경 |
| 관리자 답변 | PATCH `/admin/inquiries/{inquiryId}/reply` | 확정한 답변·처리 상태 |
| 알림 확인 | GET `/notifications/me`, `/notifications/me/unread-count` | 최근 알림과 미읽음 개수 |
| 읽음 처리 | PATCH `/notifications/{notificationId}/read`, `/notifications/me/read-all` | 반영된 읽음 상태 |

## 관련 코드와 검증

- 공지 노출: [NoticeService](../../src/main/java/com/ubot/notice/service/NoticeService.java)
- 문의 상태: [InquiryService](../../src/main/java/com/ubot/inquiry/service/InquiryService.java)
- 알림과 예약 작업: [NotificationService](../../src/main/java/com/ubot/notification/service/NotificationService.java), [NotificationReminderScheduler](../../src/main/java/com/ubot/notification/reminder/NotificationReminderScheduler.java)
- 회귀 테스트: [공지](../../src/test/java/com/ubot/notice/NoticeIntegrationTest.java), [문의](../../src/test/java/com/ubot/inquiry/service/InquiryServiceTest.java), [알림 권한](../../src/test/java/com/ubot/notification/controller/NotificationControllerTest.java), [리마인더](../../src/test/java/com/ubot/notification/reminder/SubscriptionReminderIntegrationTest.java)
