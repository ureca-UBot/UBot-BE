package com.ubot.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.billing.entity.BillingRecord;
import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.ContractType;
import com.ubot.notification.enums.DataUsageStage;
import com.ubot.notification.enums.DateReminderStage;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.enums.ReservationReminderStage;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationPurpose;
import com.ubot.store.entity.Store;
import com.ubot.subscription.entity.Subscription;
import com.ubot.subscription.entity.SubscriptionRoaming;
import com.ubot.usage.repository.DataUsageTarget;
import com.ubot.user.entity.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("알림 서비스 단위 테스트")
class NotificationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 9, 0);

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(notificationRepository);

    @Test
    @DisplayName("읽지 않은 알림 개수를 반환한다")
    void returnsUnreadCount() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(1L)).thenReturn(3L);

        assertThat(service.getUnreadCount(1L).unreadCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("전체 읽음 처리는 새로 읽음 처리한 개수를 반환한다")
    void readsAllNotifications() {
        when(notificationRepository.markAllRead(eq(1L), any(LocalDateTime.class))).thenReturn(4);

        assertThat(service.readAllNotifications(1L).updatedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("24시간 전 단계는 방문이 내일이면 내일로 안내하고 예약·단계별 dedupe_key로 저장한다")
    void createsDayBeforeReminderForTomorrow() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 16, 10, 0));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        boolean created = service.createReservationReminder(reservation, ReservationReminderStage.D1, NOW);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertIfAbsent(
                eq(1L),
                eq("RESERVATION_REMINDER"),
                eq("방문 예약 리마인더"),
                message.capture(),
                eq(100L),
                eq("RESERVATION_REMINDER:100:D1"),
                any(LocalDateTime.class));
        assertThat(message.getValue()).isEqualTo("강남점 10월 16일 (금) 10:00 요금제 변경 방문이 내일입니다.");
    }

    @Test
    @DisplayName("24시간 전 단계는 방문이 오늘이면 오늘로 안내한다")
    void createsDayBeforeReminderForToday() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 15, 20, 0));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createReservationReminder(reservation, ReservationReminderStage.D1, NOW);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertIfAbsent(
                any(), any(), any(), message.capture(), any(), any(), any());
        assertThat(message.getValue()).isEqualTo("강남점 10월 15일 (목) 20:00 요금제 변경 방문이 오늘입니다.");
    }

    @Test
    @DisplayName("6시간 전 단계는 실제 남은 시간으로 안내하고 단계별 dedupe_key로 저장한다")
    void createsSixHoursBeforeReminder() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 15, 14, 30));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createReservationReminder(reservation, ReservationReminderStage.H6, NOW);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertIfAbsent(
                any(), any(), any(), message.capture(), eq(100L), eq("RESERVATION_REMINDER:100:H6"),
                any());
        assertThat(message.getValue()).isEqualTo("강남점 10월 15일 (목) 14:30 요금제 변경 방문이 5시간 30분 남았습니다.");
    }

    @Test
    @DisplayName("1시간 전 단계는 실제 남은 시간으로 안내하고 단계별 dedupe_key로 저장한다")
    void createsOneHourBeforeReminder() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 15, 9, 30));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createReservationReminder(reservation, ReservationReminderStage.H1, NOW);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertIfAbsent(
                any(), any(), any(), message.capture(), eq(100L), eq("RESERVATION_REMINDER:100:H1"),
                any());
        assertThat(message.getValue()).isEqualTo("강남점 10월 15일 (목) 09:30 요금제 변경 방문이 30분 남았습니다.");
    }

    @Test
    @DisplayName("이미 같은 예약의 같은 단계 리마인더가 있으면 false를 반환한다")
    void returnsFalseWhenReservationReminderAlreadyExists() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 16, 10, 0));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThat(service.createReservationReminder(reservation, ReservationReminderStage.D1, NOW)).isFalse();
    }

    @Test
    @DisplayName("납부 기한 임박 알림은 남은 일수와 낼 금액을 안내하고 청구별 dedupe_key로 저장한다")
    void createsDueSoonReminderWithDaysLeft() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 18));
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        boolean created = service.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("BILLING_DUE_SOON"),
                eq("납부 기한 안내"),
                message.capture(),
                eq("BILLING_DUE_SOON:7"),
                any(LocalDateTime.class));
        assertThat(message.getValue())
                .isEqualTo("10월 요금 30,000원의 납부 기한이 3일 남았습니다. (기한: 10월 18일)");
    }

    @Test
    @DisplayName("납부 기한이 오늘이면 오늘이라고 안내한다")
    void createsDueSoonReminderOnDueDate() {
        BillingRecord billing = billing(TODAY);
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), any(), any());
        assertThat(message.getValue()).isEqualTo("10월 요금 30,000원의 납부 기한이 오늘입니다.");
    }

    @Test
    @DisplayName("연체 알림은 납부 기한이 지났음을 안내하고 청구별 dedupe_key로 저장한다")
    void createsOverdueReminder() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 10));
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createBillingReminder(billing, NotificationType.BILLING_OVERDUE, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("BILLING_OVERDUE"),
                eq("요금 연체 안내"),
                message.capture(),
                eq("BILLING_OVERDUE:7"),
                any(LocalDateTime.class));
        assertThat(message.getValue())
                .isEqualTo("10월 요금 30,000원이 납부 기한(10월 10일)을 지났습니다. 빠른 납부를 부탁드립니다.");
    }

    @Test
    @DisplayName("이미 같은 청구의 알림이 있으면 false를 반환한다")
    void returnsFalseWhenBillingReminderAlreadyExists() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 18));
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThat(service.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON, TODAY)).isFalse();
    }

    @Test
    @DisplayName("요금 리마인더가 아닌 유형으로는 만들 수 없다")
    void rejectsNonBillingType() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 18));

        assertThatThrownBy(() -> service.createBillingReminder(billing, NotificationType.RESERVATION_REMINDER,
                TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notificationRepository, never())
                .insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("로밍 시작 알림은 시작이 내일이면 내일로 안내하고 로밍·단계별 dedupe_key로 저장한다")
    void createsRoamingStartReminderForTomorrow() {
        SubscriptionRoaming roaming = roaming(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 18));
        stubInsertWithoutReservation(1);

        boolean created = service.createRoamingStartReminder(roaming, DateReminderStage.D1, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("ROAMING_START"),
                eq("로밍 시작 안내"),
                message.capture(),
                eq("ROAMING_START:30:D1"),
                any(LocalDateTime.class));
        assertThat(message.getValue()).isEqualTo("일본 3일 로밍 이용이 내일부터 시작됩니다. (10월 16일 ~ 10월 18일)");
    }

    @Test
    @DisplayName("로밍 시작 알림은 시작이 오늘이면 오늘로 안내한다")
    void createsRoamingStartReminderForToday() {
        SubscriptionRoaming roaming = roaming(TODAY, LocalDate.of(2026, 10, 17));
        stubInsertWithoutReservation(1);

        service.createRoamingStartReminder(roaming, DateReminderStage.D0, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), eq("ROAMING_START:30:D0"), any());
        assertThat(message.getValue()).isEqualTo("일본 3일 로밍 이용이 오늘부터 시작됩니다. (10월 15일 ~ 10월 17일)");
    }

    @Test
    @DisplayName("로밍 종료 알림은 종료가 내일이면 종료일과 함께 안내한다")
    void createsRoamingEndReminderForTomorrow() {
        SubscriptionRoaming roaming = roaming(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 16));
        stubInsertWithoutReservation(1);

        service.createRoamingEndReminder(roaming, DateReminderStage.D1, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("ROAMING_END"),
                eq("로밍 종료 안내"),
                message.capture(),
                eq("ROAMING_END:30:D1"),
                any(LocalDateTime.class));
        assertThat(message.getValue()).isEqualTo("일본 3일 로밍 이용이 내일 종료됩니다. (종료일: 10월 16일)");
    }

    @Test
    @DisplayName("로밍 종료 알림은 종료가 오늘이면 오늘로 안내한다")
    void createsRoamingEndReminderForToday() {
        SubscriptionRoaming roaming = roaming(LocalDate.of(2026, 10, 12), TODAY);
        stubInsertWithoutReservation(1);

        service.createRoamingEndReminder(roaming, DateReminderStage.D0, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), eq("ROAMING_END:30:D0"), any());
        assertThat(message.getValue()).isEqualTo("일본 3일 로밍 이용이 오늘 종료됩니다.");
    }

    @Test
    @DisplayName("약정 만료 알림은 남은 일수와 만료일을 안내하고 계약·단계별 dedupe_key로 저장한다")
    void createsContractReminderWithDaysLeft() {
        SubscriptionContract contract = contract(ContractType.SELECTIVE_CONTRACT, LocalDate.of(2026, 10, 22));
        stubInsertWithoutReservation(1);

        boolean created = service.createContractReminder(contract, DateReminderStage.D7, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("CONTRACT_EXPIRING"),
                eq("약정 만료 안내"),
                message.capture(),
                eq("CONTRACT_EXPIRING:40:D7"),
                any(LocalDateTime.class));
        assertThat(message.getValue()).isEqualTo("선택약정 만료가 7일 남았습니다. (만료일: 10월 22일)");
    }

    @Test
    @DisplayName("약정이 오늘 만료되면 오늘이라고 안내한다")
    void createsContractReminderOnExpiryDate() {
        SubscriptionContract contract = contract(ContractType.DEVICE_SUBSIDY, TODAY);
        stubInsertWithoutReservation(1);

        service.createContractReminder(contract, DateReminderStage.D1, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), eq("CONTRACT_EXPIRING:40:D1"), any());
        assertThat(message.getValue()).isEqualTo("단말 지원금 약정이 오늘 만료됩니다.");
    }

    @Test
    @DisplayName("이미 같은 계약의 같은 단계 약정 알림이 있으면 false를 반환한다")
    void returnsFalseWhenContractReminderAlreadyExists() {
        SubscriptionContract contract = contract(ContractType.SELECTIVE_CONTRACT, LocalDate.of(2026, 10, 22));
        stubInsertWithoutReservation(0);

        assertThat(service.createContractReminder(contract, DateReminderStage.D7, TODAY)).isFalse();
    }

    @Test
    @DisplayName("할부 만료 알림은 남은 일수와 남은 할부금을 안내하고 계약·단계별 dedupe_key로 저장한다")
    void createsInstallmentReminderWithDaysLeft() {
        SubscriptionContract contract = contract(ContractType.DEVICE_SUBSIDY, TODAY.plusDays(30));
        stubInsertWithoutReservation(1);

        boolean created = service.createInstallmentReminder(contract, DateReminderStage.D30, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("INSTALLMENT_EXPIRING"),
                eq("할부 만료 안내"),
                message.capture(),
                eq("INSTALLMENT_EXPIRING:40:D30"),
                any(LocalDateTime.class));
        assertThat(message.getValue())
                .isEqualTo("테스트 단말 할부 만료가 30일 남았습니다. (만료일: 11월 14일, 남은 할부금 750,000원)");
    }

    @Test
    @DisplayName("할부가 오늘 만료되면 오늘이라고 안내한다")
    void createsInstallmentReminderOnExpiryDate() {
        SubscriptionContract contract = SubscriptionContract.builder()
                .contractId(40L)
                .subscription(Subscription.builder().user(User.builder().id(5L).build()).build())
                .contractType(ContractType.DEVICE_SUBSIDY)
                .deviceName("테스트 단말")
                .remainingInstallmentAmount(50_000)
                .installmentExpiresAt(TODAY)
                .build();
        stubInsertWithoutReservation(1);

        service.createInstallmentReminder(contract, DateReminderStage.D1, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), eq("INSTALLMENT_EXPIRING:40:D1"), any());
        assertThat(message.getValue()).isEqualTo("테스트 단말 할부가 오늘 만료됩니다. (남은 할부금 50,000원)");
    }

    @Test
    @DisplayName("데이터 80% 단계는 실제 사용률과 남은 데이터(GB)를 안내하고 사용 기록·단계별 dedupe_key로 저장한다")
    void createsDataUsageReminderForEightyPercent() {
        DataUsageTarget target = dataUsageTarget(8_600, 10_000);
        stubInsertWithoutReservation(1);

        boolean created = service.createDataUsageReminder(target, DataUsageStage.P80, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("DATA_USAGE_THRESHOLD"),
                eq("데이터 사용량 안내"),
                message.capture(),
                eq("DATA_USAGE_THRESHOLD:60:80"),
                any(LocalDateTime.class));
        assertThat(message.getValue()).isEqualTo("10월 데이터를 86% 사용했습니다. (남은 데이터 1.4GB)");
    }

    @Test
    @DisplayName("남은 데이터가 1GB 미만이면 MB로 안내한다")
    void createsDataUsageReminderWithRemainingMegabytes() {
        DataUsageTarget target = dataUsageTarget(9_500, 10_000);
        stubInsertWithoutReservation(1);

        service.createDataUsageReminder(target, DataUsageStage.P80, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), any(), any());
        assertThat(message.getValue()).isEqualTo("10월 데이터를 95% 사용했습니다. (남은 데이터 500MB)");
    }

    @Test
    @DisplayName("데이터 100% 단계는 모두 사용했음을 안내한다")
    void createsDataUsageReminderForHundredPercent() {
        DataUsageTarget target = dataUsageTarget(10_500, 10_000);
        stubInsertWithoutReservation(1);

        service.createDataUsageReminder(target, DataUsageStage.P100, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), eq("DATA_USAGE_THRESHOLD:60:100"), any());
        assertThat(message.getValue()).isEqualTo("10월 기본 제공 데이터를 모두 사용했습니다.");
    }

    @Test
    @DisplayName("이미 같은 사용 기록의 같은 단계 알림이 있으면 false를 반환한다")
    void returnsFalseWhenDataUsageReminderAlreadyExists() {
        DataUsageTarget target = dataUsageTarget(8_600, 10_000);
        stubInsertWithoutReservation(0);

        assertThat(service.createDataUsageReminder(target, DataUsageStage.P80, TODAY)).isFalse();
    }

    private StoreReservation reservation(LocalDateTime visitAt) {
        Store store = mock(Store.class);
        when(store.getStoreName()).thenReturn("강남점");
        StoreReservation reservation = mock(StoreReservation.class);
        when(reservation.getReservationId()).thenReturn(100L);
        when(reservation.getUserId()).thenReturn(1L);
        when(reservation.getStore()).thenReturn(store);
        when(reservation.getPurpose()).thenReturn(ReservationPurpose.PLAN_CHANGE);
        when(reservation.getVisitAt()).thenReturn(visitAt);
        return reservation;
    }

    // 총 청구액 35,000원 중 5,000원을 냈으므로 낼 금액은 30,000원이다.
    private BillingRecord billing(LocalDate dueDate) {
        return BillingRecord.builder()
                .billingId(7L)
                .subscription(Subscription.builder().user(User.builder().id(5L).build()).build())
                .billingMonth(LocalDate.of(2026, 10, 1))
                .totalAmount(35_000)
                .paidAmount(5_000)
                .dueDate(dueDate)
                .build();
    }

    private void stubInsertWithoutReservation(int result) {
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    private SubscriptionRoaming roaming(LocalDate startDate, LocalDate endDate) {
        return SubscriptionRoaming.builder()
                .subscriptionRoamingId(30L)
                .subscription(Subscription.builder().user(User.builder().id(5L).build()).build())
                .productNameSnapshot("일본 3일 로밍")
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    // 단말 할부 750,000원이 남은 계약. 할부 만료일은 계약 만료일과 같다.
    private SubscriptionContract contract(ContractType type, LocalDate expiresAt) {
        return SubscriptionContract.builder()
                .contractId(40L)
                .subscription(Subscription.builder().user(User.builder().id(5L).build()).build())
                .contractType(type)
                .expiresAt(expiresAt)
                .deviceName("테스트 단말")
                .remainingInstallmentAmount(750_000)
                .installmentExpiresAt(expiresAt)
                .build();
    }

    private DataUsageTarget dataUsageTarget(long usedMb, long allowanceMb) {
        DataUsageTarget target = mock(DataUsageTarget.class);
        when(target.getUsageId()).thenReturn(60L);
        when(target.getUserId()).thenReturn(5L);
        when(target.getDataUsedMb()).thenReturn(usedMb);
        when(target.getAllowanceMb()).thenReturn(allowanceMb);
        return target;
    }
}