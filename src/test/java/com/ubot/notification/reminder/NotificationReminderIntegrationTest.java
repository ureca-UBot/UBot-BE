package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.billing.entity.BillingRecord;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.service.NotificationService;
import com.ubot.subscription.entity.Subscription;
import com.ubot.user.entity.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("리마인더 알림 통합 테스트")
class NotificationReminderIntegrationTest {
    @Autowired
    private ReservationReminderService reminderService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long storeId;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        storeId = jdbcTemplate.queryForObject(
                "INSERT INTO stores (store_name, address, latitude, longitude, business_hours) VALUES (?, ?, 37.5, 127.0, '10:00-13:00') RETURNING store_id",
                Long.class, "리마인더테스트점-" + UUID.randomUUID(), "서울 테스트로 " + UUID.randomUUID());
        now = LocalDateTime.now();
    }

    @Test
    @DisplayName("충분히 일찍 잡은 예약에는 24시간 전 리마인더를 한 번만 만들고 다시 실행해도 중복 생성하지 않는다")
    void createsReservationReminderOnlyOnce() {
        Long userId = user();
        Long reservationId = reservation(userId, now.plusHours(20), "RESERVED", now.minusDays(3));

        int sendFirst = reminderService.sendReminders(now);
        int sendSecond = reminderService.sendReminders(now);

        assertThat(sendFirst).isGreaterThanOrEqualTo(1);
        assertThat(sendSecond).isZero();
        assertThat(reminderKeys(reservationId))
                .containsExactly("RESERVATION_REMINDER:" + reservationId + ":D1");
    }

    @Test
    @DisplayName("방문이 가까워질수록 24시간·6시간·1시간 전 알림을 단계별로 한 번씩 만든다")
    void createsReminderForEachStageAsVisitApproaches() {
        Long userId = user();
        LocalDateTime visitAt = now.plusHours(20);
        Long reservationId = reservation(userId, visitAt, "RESERVED", now.minusDays(3));

        reminderService.sendReminders(now);
        reminderService.sendReminders(visitAt.minusHours(5));
        reminderService.sendReminders(visitAt.minusMinutes(30));
        reminderService.sendReminders(visitAt.minusMinutes(30));

        assertThat(reminderKeys(reservationId)).containsExactlyInAnyOrder(
                "RESERVATION_REMINDER:" + reservationId + ":D1",
                "RESERVATION_REMINDER:" + reservationId + ":H6",
                "RESERVATION_REMINDER:" + reservationId + ":H1");
    }

    @Test
    @DisplayName("그 단계 시점보다 늦게 잡은 예약과 취소된 예약에는 리마인더를 만들지 않는다")
    void skipsRecentlyBookedAndCanceledReservations() {
        Long recentUser = user();
        Long canceledUser = user();
        Long recent = reservation(recentUser, now.plusHours(20), "RESERVED", now.minusHours(1));
        Long canceled = reservation(canceledUser, now.plusHours(21), "CANCELED", now.minusDays(3));

        reminderService.sendReminders(now);

        assertThat(reminderKeys(recent)).isEmpty();
        assertThat(reminderKeys(canceled)).isEmpty();
    }

    @Test
    @DisplayName("방문까지 24시간보다 많이 남은 예약에는 리마인더를 만들지 않는다")
    void skipsReservationMoreThan24HoursAway() {
        Long userId = user();
        Long reservationId = reservation(userId, now.plusHours(30), "RESERVED", now.minusDays(3));

        reminderService.sendReminders(now);

        assertThat(reminderKeys(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("요금 리마인더는 예약 없이 한 번만 만들고 다시 만들어도 중복 생성하지 않는다")
    void createsBillingReminderOnlyOnce() {
        Long userId = user();
        long billingId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        BillingRecord billing = BillingRecord.builder()
                .billingId(billingId)
                .subscription(Subscription.builder().user(User.builder().id(userId).build()).build())
                .billingMonth(LocalDate.now().withDayOfMonth(1))
                .totalAmount(35_000)
                .paidAmount(0)
                .dueDate(LocalDate.now().plusDays(2))
                .build();

        boolean first = notificationService.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON,
                LocalDate.now());
        boolean second = notificationService.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON,
                LocalDate.now());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE dedupe_key = ?",
                Long.class, "BILLING_DUE_SOON:" + billingId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM notifications WHERE dedupe_key = ?",
                Long.class, "BILLING_DUE_SOON:" + billingId)).isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reservation_id FROM notifications WHERE dedupe_key = ?",
                Long.class, "BILLING_DUE_SOON:" + billingId)).isNull();
    }

    private List<String> reminderKeys(Long reservationId) {
        return jdbcTemplate.queryForList(
                "SELECT dedupe_key FROM notifications WHERE reservation_id = ? AND type = 'RESERVATION_REMINDER'",
                String.class, reservationId);
    }

    private Long user() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'hash', '예약자', 'USER') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
    }

    private Long reservation(Long userId, LocalDateTime visitAt, String status, LocalDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO store_reservations (user_id, store_id, purpose, visit_at, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'PLAN_CHANGE', ?, ?, ?, ?) RETURNING reservation_id",
                Long.class, userId, storeId, visitAt, status, createdAt, createdAt);
    }
}