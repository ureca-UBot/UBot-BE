package com.ubot.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.ubot.PgvectorTestConfiguration;

// 프로시저가 안에서 COMMIT 하므로 테스트 트랜잭션(@Transactional) 안에서는 실행할 수 없습니다.
// 대신 만든 행을 기록해 두고 @AfterEach에서 직접 지웁니다.
@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("보관 기간이 지난 데이터 삭제 프로시저 통합 테스트")
class DataRetentionPurgeIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TransactionTemplate transactionTemplate;

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> categoryIds = new ArrayList<>();
	private final List<Long> planIds = new ArrayList<>();
	private final List<Long> storeIds = new ArrayList<>();
	private final List<Long> groupIds = new ArrayList<>();

	private final LocalDateTime now = LocalDateTime.now();
	private final LocalDateTime fourMonthsAgo = now.minusMonths(4);
	private final LocalDateTime fourYearsAgo = now.minusYears(4);

	@AfterEach
	void cleanUp() {
		// 구독이 회원 삭제를 막으므로 먼저 지우고, 나머지는 회원·마스터 삭제의 CASCADE로 정리합니다.
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM subscriptions WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM store_reservations WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM retention.withdrawn_user_records WHERE user_id = ?", userId);
		}
		for (Long groupId : groupIds) {
			jdbcTemplate.update("DELETE FROM unanswered_question_groups WHERE id = ?", groupId);
		}
		for (Long categoryId : categoryIds) {
			jdbcTemplate.update("DELETE FROM faq_log WHERE faq_id IN (SELECT id FROM faq WHERE category_id = ?)", categoryId);
			jdbcTemplate.update("DELETE FROM faq WHERE category_id = ?", categoryId);
			jdbcTemplate.update("DELETE FROM faq_category WHERE id = ?", categoryId);
		}
		for (Long planId : planIds) {
			jdbcTemplate.update("DELETE FROM plans WHERE plan_id = ?", planId);
		}
		for (Long storeId : storeIds) {
			jdbcTemplate.update("DELETE FROM stores WHERE store_id = ?", storeId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM notices WHERE created_by = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
		}
	}

	@Test
	@DisplayName("마지막 활동이 3개월 지난 대화는 질문·검색·답변 시도·미답변 질문과 함께 지워지고 진행 중인 대화는 남는다")
	void purgesConversationsInactiveForThreeMonths() {
		Long userId = createUser(null);
		Long faqId = createFaq(createCategory(null), userId, null);
		Long oldConversation = createConversation(userId, fourMonthsAgo);
		Long activeConversation = createConversation(userId, now.minusDays(1));
		Long oldQuestion = createQuestionLog(userId, oldConversation, fourMonthsAgo);
		Long activeQuestion = createQuestionLog(userId, activeConversation, fourMonthsAgo);
		createFaqLog(oldQuestion, faqId);
		createFaqLog(activeQuestion, faqId);
		Long oldAttempt = createAttempt(userId, oldConversation, fourMonthsAgo);
		Long pendingGroup = createUnansweredGroup("PENDING", fourMonthsAgo);
		Long unansweredQuestion = createUnansweredQuestion(oldAttempt, pendingGroup);

		purge();

		assertThat(exists("conversations", "conversation_id", oldConversation)).isFalse();
		assertThat(exists("question_log", "id", oldQuestion)).isFalse();
		assertThat(count("faq_log", "question_log_id", oldQuestion)).isZero();
		assertThat(exists("answer_attempts_history", "attempt_id", oldAttempt)).isFalse();
		assertThat(exists("unanswered_questions", "id", unansweredQuestion)).isFalse();
		// 진행 중인 대화는 안의 질문이 오래됐어도 대화 단위로 남깁니다.
		assertThat(exists("conversations", "conversation_id", activeConversation)).isTrue();
		assertThat(exists("question_log", "id", activeQuestion)).isTrue();
		// 검토 대기 중인 그룹은 질문이 모두 지워져도 남깁니다.
		assertThat(exists("unanswered_question_groups", "id", pendingGroup)).isTrue();
	}

	@Test
	@DisplayName("처리가 끝난 미답변 그룹만 3개월 뒤 지워진다")
	void purgesOnlyClosedUnansweredGroups() {
		Long approved = createUnansweredGroup("APPROVED", fourMonthsAgo);
		Long onHold = createUnansweredGroup("ON_HOLD", fourMonthsAgo);
		Long recentlyApproved = createUnansweredGroup("APPROVED", now.minusDays(1));

		purge();

		assertThat(exists("unanswered_question_groups", "id", approved)).isFalse();
		assertThat(exists("unanswered_question_groups", "id", onHold)).isTrue();
		assertThat(exists("unanswered_question_groups", "id", recentlyApproved)).isTrue();
	}

	@Test
	@DisplayName("방문일이 3개월 지난 예약은 상태와 관계없이 알림과 함께 지워지고 앞으로의 예약은 남는다")
	void purgesReservationsByVisitDate() {
		Long userId = createUser(null);
		Long storeId = createStore(null);
		Long pastReservation = createReservation(userId, storeId, fourMonthsAgo);
		Long futureReservation = createReservation(userId, storeId, now.plusDays(1));
		Long notification = createNotification(userId, pastReservation, now.minusDays(1));

		purge();

		assertThat(exists("store_reservations", "reservation_id", pastReservation)).isFalse();
		assertThat(exists("notifications", "notification_id", notification)).isFalse();
		assertThat(exists("store_reservations", "reservation_id", futureReservation)).isTrue();
	}

	@Test
	@DisplayName("탈퇴 후 3개월 지난 회원은 지워지고 남은 로그는 함께, 감사 컬럼은 비워진다")
	void deletesWithdrawnUserWithoutSubscriptions() {
		Long withdrawn = createUser(fourMonthsAgo);
		Long recentlyWithdrawn = createUser(now.minusMonths(1));
		Long conversation = createConversation(withdrawn, now.minusDays(1));
		Long notice = createNotice(withdrawn, null);
		jdbcTemplate.update("INSERT INTO refresh_tokens (user_id, token, expired_at) VALUES (?, ?, ?)",
				withdrawn, UUID.randomUUID().toString(), now.plusDays(1));

		purge();

		assertThat(exists("users", "user_id", withdrawn)).isFalse();
		assertThat(exists("conversations", "conversation_id", conversation)).isFalse();
		assertThat(count("refresh_tokens", "user_id", withdrawn)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT created_by FROM notices WHERE notice_id = ?", Long.class, notice)).isNull();
		assertThat(exists("users", "user_id", recentlyWithdrawn)).isTrue();
		jdbcTemplate.update("DELETE FROM notices WHERE notice_id = ?", notice);
	}

	@Test
	@DisplayName("구독 기록이 남은 탈퇴 회원은 이름·이메일을 분리 보관하고 개인정보 컬럼을 비운다")
	void movesWithdrawnUserWithSubscriptionsToRetention() {
		Long withdrawn = createUser(fourMonthsAgo);
		String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE user_id = ?", String.class, withdrawn);
		createSubscription(withdrawn, createPlan(null), now.minusYears(1));

		purge();
		purge();

		assertThat(jdbcTemplate.queryForObject(
				"SELECT email FROM retention.withdrawn_user_records WHERE user_id = ?", String.class, withdrawn))
				.isEqualTo(email);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT email FROM users WHERE user_id = ?", String.class, withdrawn))
				.isEqualTo("deleted-" + withdrawn);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT birth_date IS NULL AND residence_area IS NULL FROM users WHERE user_id = ?", Boolean.class, withdrawn))
				.isTrue();
	}

	@Test
	@DisplayName("삭제 후 3년 지난 마스터는 참조가 없을 때만 지워진다")
	void deletesUnreferencedMastersOnly() {
		Long userId = createUser(null);
		Long referencedPlan = createPlan(fourYearsAgo);
		Long unreferencedPlan = createPlan(fourYearsAgo);
		Long recentlyDeletedPlan = createPlan(now.minusYears(1));
		createSubscription(userId, referencedPlan, null);
		Long category = createCategory(fourYearsAgo);
		Long deletedFaq = createFaq(category, userId, fourYearsAgo);
		jdbcTemplate.update("""
				INSERT INTO old_faq (faq_id, version, category_id, question, answer, updated_at, created_by, updated_by)
				VALUES (?, 1, ?, 'q', 'a', ?, ?, ?)
				""", deletedFaq, category, fourYearsAgo, userId, userId);

		purge();

		assertThat(exists("plans", "plan_id", referencedPlan)).isTrue();
		assertThat(exists("plans", "plan_id", unreferencedPlan)).isFalse();
		assertThat(exists("plans", "plan_id", recentlyDeletedPlan)).isTrue();
		assertThat(exists("faq", "id", deletedFaq)).isFalse();
		assertThat(count("old_faq", "faq_id", deletedFaq)).isZero();
		// FAQ가 먼저 지워져 참조가 사라졌으므로 같은 실행에서 카테고리도 지워집니다.
		assertThat(exists("faq_category", "id", category)).isFalse();
	}

	@Test
	@DisplayName("트랜잭션 안에서 호출하면 프로시저 안의 COMMIT 때문에 실패한다")
	void failsInsideTransaction() {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
				status -> jdbcTemplate.execute("CALL purge_expired_data()")))
				.hasMessageContaining("invalid transaction termination");
	}

	private void purge() {
		jdbcTemplate.execute("CALL purge_expired_data()");
	}

	private boolean exists(String table, String column, Long id) {
		return count(table, column, id) > 0;
	}

	private int count(String table, String column, Long id) {
		Integer result = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
		return result == null ? 0 : result;
	}

	private Long createUser(LocalDateTime deletedAt) {
		Long id = jdbcTemplate.queryForObject("""
				INSERT INTO users (email, password_hash, name, role, birth_date, residence_area, deleted_at)
				VALUES (?, 'test-only', '보관 테스트', 'USER', DATE '2000-01-01', '서울', ?)
				RETURNING user_id
				""", Long.class, "retention-" + UUID.randomUUID() + "@test.com", deletedAt);
		userIds.add(id);
		return id;
	}

	private Long createCategory(LocalDateTime deletedAt) {
		Long id = jdbcTemplate.queryForObject(
				"INSERT INTO faq_category (name, deleted_at) VALUES (?, ?) RETURNING id",
				Long.class, "보관 테스트 " + UUID.randomUUID(), deletedAt);
		categoryIds.add(id);
		return id;
	}

	private Long createFaq(Long categoryId, Long adminId, LocalDateTime deletedAt) {
		// 다른 테스트가 id를 직접 넣어 identity 시퀀스가 뒤처질 수 있어 현재 최댓값 다음 id를 씁니다.
		return jdbcTemplate.queryForObject("""
				INSERT INTO faq (id, category_id, question, answer, admin_id, deleted_at)
				SELECT COALESCE(MAX(id), 0) + 1, ?, 'q', 'a', ?, ? FROM faq
				RETURNING id
				""", Long.class, categoryId, adminId, deletedAt);
	}

	private Long createConversation(Long userId, LocalDateTime updatedAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO conversations (user_id, type, created_at, updated_at)
				VALUES (?, 'MEMBER', ?, ?)
				RETURNING conversation_id
				""", Long.class, userId, updatedAt, updatedAt);
	}

	private Long createQuestionLog(Long userId, Long conversationId, LocalDateTime createdAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO question_log (user_id, user_question, conversation_id, created_at)
				VALUES (?, 'q', ?, ?)
				RETURNING id
				""", Long.class, userId, conversationId, createdAt);
	}

	private void createFaqLog(Long questionLogId, Long faqId) {
		jdbcTemplate.update(
				"INSERT INTO faq_log (question_log_id, faq_id, rank, similarity) VALUES (?, ?, 1, 0.9)",
				questionLogId, faqId);
	}

	private Long createAttempt(Long userId, Long conversationId, LocalDateTime createdAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO answer_attempts_history (user_id, question, attempt_count, idempotency_key, conversation_id, created_at)
				VALUES (?, 'q', 1, ?, ?, ?)
				RETURNING attempt_id
				""", Long.class, userId, UUID.randomUUID().toString().replace("-", ""), conversationId, createdAt);
	}

	private Long createUnansweredGroup(String status, LocalDateTime updatedAt) {
		Long id = jdbcTemplate.queryForObject("""
				INSERT INTO unanswered_question_groups (representative_question, status, updated_at)
				VALUES ('g', ?, ?)
				RETURNING id
				""", Long.class, status, updatedAt);
		groupIds.add(id);
		return id;
	}

	private Long createUnansweredQuestion(Long attemptId, Long groupId) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO unanswered_questions (attempt_id, group_id, question, reason)
				VALUES (?, ?, 'q', 'NO_FAQ')
				RETURNING id
				""", Long.class, attemptId, groupId);
	}

	private Long createStore(LocalDateTime deletedAt) {
		Long id = jdbcTemplate.queryForObject("""
				INSERT INTO stores (store_name, address, latitude, longitude, deleted_at)
				VALUES (?, '보관 테스트 주소', 37.5, 127.0, ?)
				RETURNING store_id
				""", Long.class, "보관 테스트 " + UUID.randomUUID(), deletedAt);
		storeIds.add(id);
		return id;
	}

	private Long createReservation(Long userId, Long storeId, LocalDateTime visitAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO store_reservations (user_id, store_id, purpose, visit_at)
				VALUES (?, ?, 'ACTIVATION', ?)
				RETURNING reservation_id
				""", Long.class, userId, storeId, visitAt);
	}

	private Long createNotification(Long userId, Long reservationId, LocalDateTime createdAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO notifications (user_id, type, title, message, reservation_id, created_at)
				VALUES (?, 'RESERVATION_CONFIRMED', 't', 'm', ?, ?)
				RETURNING notification_id
				""", Long.class, userId, reservationId, createdAt);
	}

	private Long createNotice(Long createdBy, LocalDateTime deletedAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO notices (type, title, content, created_by, deleted_at)
				VALUES ('NOTICE', 'n', 'c', ?, ?)
				RETURNING notice_id
				""", Long.class, createdBy, deletedAt);
	}

	private Long createPlan(LocalDateTime deletedAt) {
		Long id = jdbcTemplate.queryForObject("""
				INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee,
				                   data_amount_mb, voice_minutes, sms_count, deleted_at)
				VALUES (?, '보관 테스트', 'LTE', 'GENERAL', 1000, 1, 1, 1, ?)
				RETURNING plan_id
				""", Long.class, "RT-" + UUID.randomUUID().toString().substring(0, 8), deletedAt);
		planIds.add(id);
		return id;
	}

	private void createSubscription(Long userId, Long planId, LocalDateTime terminatedAt) {
		jdbcTemplate.update("""
				INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at, terminated_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
				userId, planId, "010-" + UUID.randomUUID().toString().substring(0, 8),
				terminatedAt == null ? "ACTIVE" : "TERMINATED", now.minusYears(2), terminatedAt);
	}
}
