package com.ubot.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.subscription.service.RoamingStatusService;

@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("로밍 상태 날짜 전환 통합 테스트")
class RoamingStatusServiceTest {

	@Autowired
	private RoamingStatusService roamingStatusService;
	@Autowired
	private JdbcTemplate jdbc;

	private final LocalDate today = LocalDate.now();
	private long subscriptionId;
	private long roamingProductId;

	@BeforeEach
	void createFixtures() {
		long userId = jdbc.queryForObject("""
				INSERT INTO users (email, password_hash, name) VALUES (?, 'test-only', '로밍 상태 테스트')
				RETURNING user_id
				""", Long.class, UUID.randomUUID() + "@test.com");
		long planId = jdbc.queryForObject("""
				INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee, data_amount_mb, voice_minutes, sms_count)
				VALUES (?, '로밍 상태 테스트', 'FIVE_G', 'GENERAL', 30000, 1000, 100, 100) RETURNING plan_id
				""", Long.class, UUID.randomUUID().toString().substring(0, 30));
		subscriptionId = jdbc.queryForObject("""
				INSERT INTO subscriptions (user_id, plan_id, phone_number, activated_at)
				VALUES (?, ?, ?, CURRENT_TIMESTAMP - INTERVAL '1 year') RETURNING subscription_id
				""", Long.class, userId, planId, "019" + UUID.randomUUID().toString().substring(0, 8));
		roamingProductId = jdbc.queryForObject(
				"SELECT roaming_product_id FROM roaming_products WHERE code = 'UBOT_ROAM_JP_1G'", Long.class);
	}

	@Test
	@DisplayName("시작일이 된 예약은 이용 중으로, 종료일이 지난 예약·이용 중 로밍은 종료로 바꾸고 나머지는 그대로 둔다")
	void updatesStatusesByDate() {
		long startsToday = roaming(today, today.plusDays(2), "SCHEDULED");
		long startedEarlier = roaming(today.minusDays(3), today, "SCHEDULED");
		long future = roaming(today.plusDays(1), today.plusDays(3), "SCHEDULED");
		long endedScheduled = roaming(today.minusDays(5), today.minusDays(1), "SCHEDULED");
		long endedActive = roaming(today.minusDays(3), today.minusDays(1), "ACTIVE");
		long stillActive = roaming(today.minusDays(1), today.plusDays(1), "ACTIVE");
		long canceled = jdbc.queryForObject("""
				INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date, end_date, status,
				    product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot, canceled_at)
				VALUES (?, ?, ?, ?, 'CANCELED', '일본', 11000, 1000, ?) RETURNING subscription_roaming_id
				""", Long.class, subscriptionId, roamingProductId, today.minusDays(5), today.minusDays(1),
				LocalDateTime.now().minusDays(10));

		roamingStatusService.updateStatuses(today);

		assertThat(status(startsToday)).isEqualTo("ACTIVE");
		assertThat(status(startedEarlier)).isEqualTo("ACTIVE");
		assertThat(status(future)).isEqualTo("SCHEDULED");
		assertThat(status(endedScheduled)).isEqualTo("EXPIRED");
		assertThat(status(endedActive)).isEqualTo("EXPIRED");
		assertThat(status(stillActive)).isEqualTo("ACTIVE");
		assertThat(status(canceled)).isEqualTo("CANCELED");

		// 같은 날 다시 실행해도 결과가 같다.
		roamingStatusService.updateStatuses(today);
		assertThat(status(startsToday)).isEqualTo("ACTIVE");
		assertThat(status(endedActive)).isEqualTo("EXPIRED");
	}

	private long roaming(LocalDate startDate, LocalDate endDate, String status) {
		return jdbc.queryForObject("""
				INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date, end_date, status,
				    product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot)
				VALUES (?, ?, ?, ?, ?, '일본', 11000, 1000) RETURNING subscription_roaming_id
				""", Long.class, subscriptionId, roamingProductId, startDate, endDate, status);
	}

	private String status(long subscriptionRoamingId) {
		return jdbc.queryForObject("SELECT status FROM subscription_roamings WHERE subscription_roaming_id = ?",
				String.class, subscriptionRoamingId);
	}
}
