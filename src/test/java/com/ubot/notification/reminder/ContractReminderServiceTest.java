package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.InstallmentStatus;
import com.ubot.contract.repository.SubscriptionContractRepository;
import com.ubot.notification.enums.DateReminderStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.subscription.enums.SubscriptionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("약정·할부 만료 리마인더 서비스 테스트")
class ContractReminderServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 1, 1, 9, 0);

    private final SubscriptionContractRepository subscriptionContractRepository = mock(
            SubscriptionContractRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ContractReminderService service = new ContractReminderService(subscriptionContractRepository,
            notificationService);

    @Test
    @DisplayName("약정 만료는 오늘부터 30일 안에 만료하는 ACTIVE 계약을 활성 회선에서 조회한다")
    void searchesContractsExpiringWithin30Days() {
        when(subscriptionContractRepository.findByExpiresAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.sendContractReminders(TODAY);

        verify(subscriptionContractRepository).findByExpiresAtBetween(
                TODAY, TODAY.plusDays(30), ContractStatus.ACTIVE, SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("남은 일수에 맞는 단계(30일·7일·1일 전)로 약정 알림을 만들고 당일은 1일 전 단계이다")
    void createsContractReminderByStage() {
        SubscriptionContract thirty = contract(1L, TODAY.plusDays(30), LONG_AGO);
        SubscriptionContract eight = contract(2L, TODAY.plusDays(8), LONG_AGO);
        SubscriptionContract seven = contract(3L, TODAY.plusDays(7), LONG_AGO);
        SubscriptionContract two = contract(4L, TODAY.plusDays(2), LONG_AGO);
        SubscriptionContract one = contract(5L, TODAY.plusDays(1), LONG_AGO);
        SubscriptionContract today = contract(6L, TODAY, LONG_AGO);
        returnContracts(thirty, eight, seven, two, one, today);
        when(notificationService.createContractReminder(any(), any(), eq(TODAY))).thenReturn(true);

        assertThat(service.sendContractReminders(TODAY)).isEqualTo(6);
        verify(notificationService).createContractReminder(thirty, DateReminderStage.D30, TODAY);
        verify(notificationService).createContractReminder(eight, DateReminderStage.D30, TODAY);
        verify(notificationService).createContractReminder(seven, DateReminderStage.D7, TODAY);
        verify(notificationService).createContractReminder(two, DateReminderStage.D7, TODAY);
        verify(notificationService).createContractReminder(one, DateReminderStage.D1, TODAY);
        verify(notificationService).createContractReminder(today, DateReminderStage.D1, TODAY);
    }

    @Test
    @DisplayName("계약을 그 단계 시점보다 늦게 등록했으면 그 단계는 건너뛴다")
    void skipsStageWhenRegisteredAfterIt() {
        // 만료까지 26일이라 30일 전 단계인데, 10월 14일에 등록해 30일 전 시점(10월 11일 0시)보다 늦다.
        SubscriptionContract contract = contract(1L, TODAY.plusDays(26), LocalDateTime.of(2026, 10, 14, 9, 0));
        returnContracts(contract);

        assertThat(service.sendContractReminders(TODAY)).isZero();
        verify(notificationService, never()).createContractReminder(any(), any(), any());
    }

    @Test
    @DisplayName("이전 단계를 놓쳤어도 다음 단계 시점보다 앞서 등록했으면 다음 단계 알림은 만든다")
    void createsNextStageWhenEarlierStageWasMissedByRegistration() {
        // 만료까지 5일(7일 전 단계). 10월 14일에 등록했고 7일 전 시점(10월 13일 0시)보다 늦다 → 건너뜀
        SubscriptionContract late = contract(1L, TODAY.plusDays(5), LocalDateTime.of(2026, 10, 14, 9, 0));
        // 만료까지 5일. 10월 12일에 등록해 7일 전 시점(10월 13일 0시)보다 앞선다 → 만든다
        SubscriptionContract early = contract(2L, TODAY.plusDays(5), LocalDateTime.of(2026, 10, 12, 9, 0));
        returnContracts(late, early);
        when(notificationService.createContractReminder(early, DateReminderStage.D7, TODAY)).thenReturn(true);

        assertThat(service.sendContractReminders(TODAY)).isEqualTo(1);
        verify(notificationService, never()).createContractReminder(eq(late), any(), any());
    }

    @Test
    @DisplayName("이미 같은 단계 알림이 있는 계약은 새로 만든 개수에 포함하지 않는다")
    void doesNotCountExistingContractReminder() {
        returnContracts(contract(1L, TODAY.plusDays(7), LONG_AGO));
        when(notificationService.createContractReminder(any(), any(), any())).thenReturn(false);

        assertThat(service.sendContractReminders(TODAY)).isZero();
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 계약은 계속 처리한다")
    void continuesContractRemindersAfterFailure() {
        SubscriptionContract failing = contract(1L, TODAY.plusDays(7), LONG_AGO);
        SubscriptionContract succeeding = contract(2L, TODAY.plusDays(7), LONG_AGO);
        returnContracts(failing, succeeding);
        doThrow(new IllegalStateException("db error"))
                .when(notificationService).createContractReminder(eq(failing), any(), any());
        when(notificationService.createContractReminder(eq(succeeding), any(), any())).thenReturn(true);

        assertThat(service.sendContractReminders(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("할부 만료는 오늘부터 30일 안에 만료하는 ACTIVE 할부를 활성 회선에서 조회한다")
    void searchesInstallmentsExpiringWithin30Days() {
        when(subscriptionContractRepository.findByInstallmentExpiresAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.sendInstallmentReminders(TODAY);

        verify(subscriptionContractRepository).findByInstallmentExpiresAtBetween(
                TODAY, TODAY.plusDays(30), InstallmentStatus.ACTIVE, SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("남은 일수에 맞는 단계로 할부 알림을 만든다")
    void createsInstallmentReminderByStage() {
        SubscriptionContract seven = installment(1L, TODAY.plusDays(7), LONG_AGO);
        SubscriptionContract today = installment(2L, TODAY, LONG_AGO);
        returnInstallments(seven, today);
        when(notificationService.createInstallmentReminder(any(), any(), eq(TODAY))).thenReturn(true);

        assertThat(service.sendInstallmentReminders(TODAY)).isEqualTo(2);
        verify(notificationService).createInstallmentReminder(seven, DateReminderStage.D7, TODAY);
        verify(notificationService).createInstallmentReminder(today, DateReminderStage.D1, TODAY);
    }

    @Test
    @DisplayName("계약을 그 단계 시점보다 늦게 등록했으면 할부 알림도 그 단계는 건너뛴다")
    void skipsInstallmentStageWhenRegisteredAfterIt() {
        SubscriptionContract contract = installment(1L, TODAY.plusDays(26), LocalDateTime.of(2026, 10, 14, 9, 0));
        returnInstallments(contract);

        assertThat(service.sendInstallmentReminders(TODAY)).isZero();
        verify(notificationService, never()).createInstallmentReminder(any(), any(), any());
    }

    private void returnContracts(SubscriptionContract... contracts) {
        when(subscriptionContractRepository.findByExpiresAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of(contracts));
    }

    private void returnInstallments(SubscriptionContract... contracts) {
        when(subscriptionContractRepository.findByInstallmentExpiresAtBetween(any(), any(), any(), any()))
                .thenReturn(List.of(contracts));
    }

    private SubscriptionContract contract(Long id, LocalDate expiresAt, LocalDateTime createdAt) {
        SubscriptionContract contract = mock(SubscriptionContract.class);
        when(contract.getContractId()).thenReturn(id);
        when(contract.getExpiresAt()).thenReturn(expiresAt);
        when(contract.getCreatedAt()).thenReturn(createdAt);
        return contract;
    }

    private SubscriptionContract installment(Long id, LocalDate installmentExpiresAt, LocalDateTime createdAt) {
        SubscriptionContract contract = mock(SubscriptionContract.class);
        when(contract.getContractId()).thenReturn(id);
        when(contract.getInstallmentExpiresAt()).thenReturn(installmentExpiresAt);
        when(contract.getCreatedAt()).thenReturn(createdAt);
        return contract;
    }
}