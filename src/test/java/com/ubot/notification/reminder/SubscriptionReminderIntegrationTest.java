package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
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
@DisplayName("구독 리마인더(로밍·약정·할부·데이터 사용량) 통합 테스트")
class SubscriptionReminderIntegrationTest {
    private static final String ALLOWANCE_10GB = "{\"dataAmountMb\":10000,\"dataUnlimited\":false}";

    @Autowired
    private RoamingReminderService roamingReminderService;
    @Autowired
    private ContractReminderService contractReminderService;
    @Autowired
    private DataUsageReminderService dataUsageReminderService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long planId;
    private Long roamingProductId;
    private LocalDate today;
    private LocalDateTime longAgo;

    @BeforeEach
    void setUp() {
        planId = jdbcTemplate.queryForObject("""
                INSERT INTO plans (plan_code, name, description, network_type, target_group, monthly_fee,
                    data_amount_mb, voice_minutes, sms_count, tethering_amount_mb, status)
                VALUES (?, '알림 테스트 요금제', '알림 테스트', 'FIVE_G', 'GENERAL', 40000,
                    10000, 100, 100, 0, 'INACTIVE') RETURNING plan_id
                """, Long.class, UUID.randomUUID().toString());
        roamingProductId = jdbcTemplate.queryForObject("""
                INSERT INTO roaming_products (code, name, daily_fee, data_amount_mb, country, status)
                VALUES (?, '알림 테스트 로밍', 10000, 1000, '일본', 'INACTIVE') RETURNING roaming_product_id
                """, Long.class, UUID.randomUUID().toString());
        today = LocalDate.now();
        longAgo = LocalDateTime.now().minusDays(100);
    }

    @Test
    @DisplayName("로밍 시작 알림은 시작 하루 전 단계로 한 번만 만들고 다시 실행해도 중복 생성하지 않는다")
    void createsRoamingStartReminderOnlyOnce() {
        Long userId = user();
        Long roamingId = roaming(subscription(userId, "ACTIVE"), today.plusDays(1), today.plusDays(3), "SCHEDULED",
                longAgo);

        roamingReminderService.sendStartReminders(today);
        int second = roamingReminderService.sendStartReminders(today);

        assertThat(second).isZero();
        assertThat(keys(userId)).containsExactly("ROAMING_START:" + roamingId + ":D1");
        assertThat(message(userId)).contains("내일부터 시작됩니다");
    }

    @Test
    @DisplayName("취소·해지·탈퇴·방금 등록한 로밍은 시작 알림을 만들지 않는다")
    void skipsIneligibleRoamingsForStartReminder() {
        Long canceledUser = user();
        roaming(subscription(canceledUser, "ACTIVE"), today.plusDays(1), today.plusDays(3), "CANCELED", longAgo);
        Long terminatedUser = user();
        roaming(subscription(terminatedUser, "TERMINATED"), today.plusDays(1), today.plusDays(3), "SCHEDULED",
                longAgo);
        Long deletedUser = user();
        roaming(subscription(deletedUser, "ACTIVE"), today.plusDays(1), today.plusDays(3), "SCHEDULED", longAgo);
        jdbcTemplate.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = ?", deletedUser);
        Long recentUser = user();
        roaming(subscription(recentUser, "ACTIVE"), today.plusDays(1), today.plusDays(3), "SCHEDULED",
                LocalDateTime.now());

        roamingReminderService.sendStartReminders(today);

        assertThat(keys(canceledUser)).isEmpty();
        assertThat(keys(terminatedUser)).isEmpty();
        assertThat(keys(deletedUser)).isEmpty();
        assertThat(keys(recentUser)).isEmpty();
    }

    @Test
    @DisplayName("로밍 종료 알림은 종료 하루 전 단계로 만들고 1일 로밍은 만들지 않는다")
    void createsRoamingEndReminderExceptOneDayRoaming() {
        Long userId = user();
        Long roamingId = roaming(subscription(userId, "ACTIVE"), today.minusDays(2), today.plusDays(1), "ACTIVE",
                longAgo);
        Long oneDayUser = user();
        roaming(subscription(oneDayUser, "ACTIVE"), today, today, "ACTIVE", longAgo);

        roamingReminderService.sendEndReminders(today);

        assertThat(keys(userId)).containsExactly("ROAMING_END:" + roamingId + ":D1");
        assertThat(keys(oneDayUser)).isEmpty();
    }

    @Test
    @DisplayName("약정 만료 알림은 7일 전 단계로 만들고 30일보다 많이 남았거나 방금 등록한 계약은 만들지 않는다")
    void createsContractReminderForExpiringContract() {
        Long userId = user();
        Long contractId = contract(subscription(userId, "ACTIVE"), today.plusDays(7), longAgo);
        Long farUser = user();
        contract(subscription(farUser, "ACTIVE"), today.plusDays(40), longAgo);
        Long recentUser = user();
        contract(subscription(recentUser, "ACTIVE"), today.plusDays(7), LocalDateTime.now());

        contractReminderService.sendContractReminders(today);
        int second = contractReminderService.sendContractReminders(today);

        assertThat(second).isZero();
        assertThat(keys(userId)).containsExactly("CONTRACT_EXPIRING:" + contractId + ":D7");
        assertThat(keys(farUser)).isEmpty();
        assertThat(keys(recentUser)).isEmpty();
    }

    @Test
    @DisplayName("할부 만료 알림은 약정 만료와 별개로 남은 할부금과 함께 만든다")
    void createsInstallmentReminder() {
        Long userId = user();
        Long contractId = installmentContract(subscription(userId, "ACTIVE"), today.plusDays(3), longAgo);

        contractReminderService.sendInstallmentReminders(today);
        contractReminderService.sendContractReminders(today);

        assertThat(keys(userId)).containsExactly("INSTALLMENT_EXPIRING:" + contractId + ":D7");
        assertThat(message(userId)).contains("남은 할부금 750,000원");
    }

    @Test
    @DisplayName("데이터는 80% 이상이면 80% 단계, 100% 이상이면 100% 단계로 한 번만 만든다")
    void createsDataUsageReminderByStage() {
        Long eightyUser = user();
        Long eighty = usage(subscription(eightyUser, "ACTIVE"), thisMonth(), 8_600, ALLOWANCE_10GB);
        Long hundredUser = user();
        Long hundred = usage(subscription(hundredUser, "ACTIVE"), thisMonth(), 10_500, ALLOWANCE_10GB);
        Long lowUser = user();
        usage(subscription(lowUser, "ACTIVE"), thisMonth(), 5_000, ALLOWANCE_10GB);

        dataUsageReminderService.sendReminders(today);
        int second = dataUsageReminderService.sendReminders(today);

        assertThat(second).isZero();
        assertThat(keys(eightyUser)).containsExactly("DATA_USAGE_THRESHOLD:" + eighty + ":80");
        assertThat(keys(hundredUser)).containsExactly("DATA_USAGE_THRESHOLD:" + hundred + ":100");
        assertThat(keys(lowUser)).isEmpty();
        assertThat(message(eightyUser)).contains("86% 사용했습니다");
    }

    @Test
    @DisplayName("사용량이 80%에서 100%로 늘면 단계별로 한 번씩 알림을 만든다")
    void createsEachStageAsUsageGrows() {
        Long userId = user();
        Long subscriptionId = subscription(userId, "ACTIVE");
        Long usageId = usage(subscriptionId, thisMonth(), 8_600, ALLOWANCE_10GB);

        dataUsageReminderService.sendReminders(today);
        jdbcTemplate.update("UPDATE usage_records SET data_used_mb = 10500 WHERE usage_id = ?", usageId);
        dataUsageReminderService.sendReminders(today);
        dataUsageReminderService.sendReminders(today);

        assertThat(keys(userId)).containsExactlyInAnyOrder(
                "DATA_USAGE_THRESHOLD:" + usageId + ":80",
                "DATA_USAGE_THRESHOLD:" + usageId + ":100");
    }

    @Test
    @DisplayName("무제한·불완전·잘못된 스냅샷, 해지·탈퇴 회선, 지난달 기록은 데이터 알림을 만들지 않고 오류도 나지 않는다")
    void skipsIneligibleUsageRecords() {
        Long unlimitedUser = user();
        usage(subscription(unlimitedUser, "ACTIVE"), thisMonth(), 9_000,
                "{\"dataAmountMb\":null,\"dataUnlimited\":true}");
        Long incompleteUser = user();
        usage(subscription(incompleteUser, "ACTIVE"), thisMonth(), 9_000, "{\"dataAmountMb\":10000}");
        Long wrongTypeUser = user();
        usage(subscription(wrongTypeUser, "ACTIVE"), thisMonth(), 9_000,
                "{\"dataAmountMb\":\"abc\",\"dataUnlimited\":false}");
        Long decimalUser = user();
        usage(subscription(decimalUser, "ACTIVE"), thisMonth(), 1_000,
                "{\"dataAmountMb\":1000.5,\"dataUnlimited\":false}");
        Long terminatedUser = user();
        usage(subscription(terminatedUser, "TERMINATED"), thisMonth(), 9_000, ALLOWANCE_10GB);
        Long deletedUser = user();
        usage(subscription(deletedUser, "ACTIVE"), thisMonth(), 9_000, ALLOWANCE_10GB);
        jdbcTemplate.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = ?", deletedUser);
        Long lastMonthUser = user();
        usage(subscription(lastMonthUser, "ACTIVE"), thisMonth().minusMonths(1), 9_000, ALLOWANCE_10GB);

        dataUsageReminderService.sendReminders(today);

        assertThat(keys(unlimitedUser)).isEmpty();
        assertThat(keys(incompleteUser)).isEmpty();
        assertThat(keys(wrongTypeUser)).isEmpty();
        assertThat(keys(decimalUser)).isEmpty();
        assertThat(keys(terminatedUser)).isEmpty();
        assertThat(keys(deletedUser)).isEmpty();
        assertThat(keys(lastMonthUser)).isEmpty();
    }

    private LocalDate thisMonth() {
        return today.withDayOfMonth(1);
    }

    private List<String> keys(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT dedupe_key FROM notifications WHERE user_id = ? ORDER BY dedupe_key",
                String.class, userId);
    }

    private String message(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE user_id = ? ORDER BY notification_id LIMIT 1",
                String.class, userId);
    }

    private Long user() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'hash', '구독자', 'USER') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
    }

    private Long subscription(Long userId, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at, terminated_at)
                VALUES (?, ?, ?, ?, TIMESTAMP '2026-01-01 00:00:00',
                    CASE WHEN ? = 'TERMINATED' THEN TIMESTAMP '2026-02-01 00:00:00' ELSE NULL END)
                RETURNING subscription_id
                """, Long.class, userId, planId, UUID.randomUUID().toString().substring(0, 30), status, status);
    }

    private Long roaming(Long subscriptionId, LocalDate startDate, LocalDate endDate, String status,
            LocalDateTime createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date, end_date, status,
                    product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot, canceled_at, created_at)
                VALUES (?, ?, ?, ?, ?, '알림 테스트 로밍', 10000, 1000,
                    CASE WHEN ? = 'CANCELED' THEN CURRENT_TIMESTAMP END, ?)
                RETURNING subscription_roaming_id
                """, Long.class, subscriptionId, roamingProductId, startDate, endDate, status, status, createdAt);
    }

    private Long contract(Long subscriptionId, LocalDate expiresAt, LocalDateTime createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, status,
                    created_at)
                VALUES (?, 'SELECTIVE_CONTRACT', ?, ?, 'ACTIVE', ?) RETURNING contract_id
                """, Long.class, subscriptionId, expiresAt.minusYears(1), expiresAt, createdAt);
    }

    // 약정은 한참 뒤(300일)에 만료되고 단말 할부만 installmentExpiresAt에 만료되는 계약
    private Long installmentContract(Long subscriptionId, LocalDate installmentExpiresAt, LocalDateTime createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, status,
                    created_at, device_name, device_total_amount, installment_months, paid_installment_months,
                    monthly_installment_amount, remaining_installment_amount, installment_started_at,
                    installment_expires_at, installment_status)
                VALUES (?, 'DEVICE_SUBSIDY', ?, ?, 'ACTIVE', ?, '알림 테스트 단말', 1200000, 24, 9, 50000, 750000,
                    ?, ?, 'ACTIVE')
                RETURNING contract_id
                """, Long.class, subscriptionId, today.minusYears(1), today.plusDays(300), createdAt,
                installmentExpiresAt.minusYears(2), installmentExpiresAt);
    }

    private Long usage(Long subscriptionId, LocalDate billingMonth, long dataUsedMb, String allowanceSnapshot) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO usage_records (subscription_id, billing_month, data_used_mb, allowance_snapshot)
                VALUES (?, ?, ?, CAST(? AS JSONB)) RETURNING usage_id
                """, Long.class, subscriptionId, billingMonth, dataUsedMb, allowanceSnapshot);
    }
}