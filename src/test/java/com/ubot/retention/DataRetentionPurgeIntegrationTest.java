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
	private final List<Long> questionLogIds = new ArrayList<>();

	private final LocalDateTime now = LocalDateTime.now();
	private final LocalDateTime fourMonthsAgo = now.minusMonths(4);
	private final LocalDateTime fourYearsAgo = now.minusYears(4);

	@AfterEach
	void cleanUp() {
		// 익명화된 질문 로그는 회원·대화 연결이 없어 CASCADE로 지워지지 않으므로 직접 지웁니다(faq_log CASCADE).
		for (Long questionLogId : questionLogIds) {
			jdbcTemplate.update("DELETE FROM question_log WHERE id = ?", questionLogId);
		}
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
	@DisplayName("마지막 활동이 3개월 지난 대화는 답변 시도와 함께 지워지고, 질문 로그는 익명화해 모니터링용으로 남긴다")
	void purgesConversationsInactiveForThreeMonths() {
		Long userId = createUser(null);
		Long faqId = createFaq(createCategory(null), userId, null);
		Long oldConversation = createConversation(userId, fourMonthsAgo);
		Long guestConversation = createGuestConversation(fourMonthsAgo);
		Long activeConversation = createConversation(userId, now.minusDays(1));
		Long oldQuestion = createQuestionLog(userId, oldConversation, fourMonthsAgo);
		Long guestQuestion = createQuestionLog(null, guestConversation, fourMonthsAgo);
		Long activeQuestion = createQuestionLog(userId, activeConversation, fourMonthsAgo);
		createFaqLog(oldQuestion, faqId);
		createFaqLog(activeQuestion, faqId);
		Long oldAttempt = createAttempt(userId, oldConversation, fourMonthsAgo);
		jdbcTemplate.update("UPDATE question_log SET attempt_id = ? WHERE id = ?", oldAttempt, oldQuestion);
		Long pendingGroup = createUnansweredGroup("PENDING", fourMonthsAgo);
		Long unansweredQuestion = createUnansweredQuestion(oldAttempt, pendingGroup);

		purge();

		assertThat(exists("conversations", "conversation_id", oldConversation)).isFalse();
		assertThat(exists("conversations", "conversation_id", guestConversation)).isFalse();
		assertThat(exists("answer_attempts_history", "attempt_id", oldAttempt)).isFalse();
		// 질문 로그와 검색 결과는 FAQ 모니터링용으로 남기고 원문과 회원·대화 연결만 비웁니다.
		assertThat(isAnonymized(oldQuestion)).isTrue();
		assertThat(isAnonymized(guestQuestion)).isTrue();
		assertThat(count("faq_log", "question_log_id", oldQuestion)).isOne();
		// 답변 시도가 먼저 지워지므로 질문 로그는 시도와의 연결만 끊깁니다.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT attempt_id FROM question_log WHERE id = ?", Long.class, oldQuestion)).isNull();
		// 진행 중인 대화는 안의 질문이 오래됐어도 대화 기록 화면에 쓰이므로 그대로 둡니다.
		assertThat(exists("conversations", "conversation_id", activeConversation)).isTrue();
		assertThat(isAnonymized(activeQuestion)).isFalse();
		// 검토 대기 중인 그룹의 미답변 질문은 시도 로그가 지워져도 연결만 끊고 남깁니다.
		assertThat(exists("unanswered_question_groups", "id", pendingGroup)).isTrue();
		assertThat(exists("unanswered_questions", "id", unansweredQuestion)).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT attempt_id FROM unanswered_questions WHERE id = ?", Long.class, unansweredQuestion)).isNull();
	}

	@Test
	@DisplayName("FAQ 모니터링 보관 기간(3년)이 지난 질문 로그는 검색 결과와 함께 지워진다")
	void purgesQuestionLogsOlderThanThreeYears() {
		Long userId = createUser(null);
		Long faqId = createFaq(createCategory(null), userId, null);
		Long expiredQuestion = createQuestionLog(userId, null, now.minusYears(3).minusDays(1));
		Long keptQuestion = createQuestionLog(userId, null, now.minusYears(2));
		createFaqLog(expiredQuestion, faqId);
		createFaqLog(keptQuestion, faqId);

		purge();

		assertThat(exists("question_log", "id", expiredQuestion)).isFalse();
		assertThat(count("faq_log", "question_log_id", expiredQuestion)).isZero();
		assertThat(isAnonymized(keptQuestion)).isTrue();
		assertThat(count("faq_log", "question_log_id", keptQuestion)).isOne();
	}

	@Test
	@DisplayName("처리가 끝난 미답변 그룹만 3개월 뒤 질문과 함께 지워진다")
	void purgesOnlyClosedUnansweredGroups() {
		Long userId = createUser(null);
		Long approved = createUnansweredGroup("APPROVED", fourMonthsAgo);
		Long onHold = createUnansweredGroup("ON_HOLD", fourMonthsAgo);
		Long recentlyApproved = createUnansweredGroup("APPROVED", now.minusDays(1));
		Long approvedQuestion = createUnansweredQuestion(createAttempt(userId, null, now.minusDays(1)), approved);
		Long onHoldQuestion = createUnansweredQuestion(createAttempt(userId, null, now.minusDays(1)), onHold);

		purge();

		assertThat(exists("unanswered_question_groups", "id", approved)).isFalse();
		assertThat(exists("unanswered_questions", "id", approvedQuestion)).isFalse();
		assertThat(exists("unanswered_question_groups", "id", onHold)).isTrue();
		assertThat(exists("unanswered_questions", "id", onHoldQuestion)).isTrue();
		assertThat(exists("unanswered_question_groups", "id", recentlyApproved)).isTrue();
	}

	@Test
	@DisplayName("재시도를 다 쓴 대기열 작업만 마지막 실패 7일 뒤 지워진다")
	void purgesOnlyExhaustedOutboxEvents() {
		Long exhausted = createOutboxEvent(5, now.minusDays(8));
		Long recentlyExhausted = createOutboxEvent(5, now.minusDays(1));
		Long retrying = createOutboxEvent(2, now.minusDays(8));

		purge();

		assertThat(exists("outbox_events", "id", exhausted)).isFalse();
		assertThat(exists("outbox_events", "id", recentlyExhausted)).isTrue();
		assertThat(exists("outbox_events", "id", retrying)).isTrue();
		jdbcTemplate.update("DELETE FROM outbox_events WHERE id IN (?, ?)", recentlyExhausted, retrying);
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

	private boolean isAnonymized(Long questionLogId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT anonymized_at IS NOT NULL AND user_question = '' AND llm_question = ''
				       AND normalized_question = '' AND sub_question = '' AND user_text = ''
				       AND user_id IS NULL AND conversation_id IS NULL
				FROM question_log WHERE id = ?
				""", Boolean.class, questionLogId));
	}

	private Long createOutboxEvent(int retryCount, LocalDateTime nextAttemptAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO outbox_events (event_type, dedup_key, payload, retry_count, next_attempt_at)
				VALUES ('UNANSWERED_QUESTION', ?, '{}'::jsonb, ?, ?)
				RETURNING id
				""", Long.class, "retention-" + UUID.randomUUID(), retryCount, nextAttemptAt);
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

	private Long createGuestConversation(LocalDateTime updatedAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO conversations (type, created_at, updated_at)
				VALUES ('GUEST', ?, ?)
				RETURNING conversation_id
				""", Long.class, updatedAt, updatedAt);
	}

	private Long createQuestionLog(Long userId, Long conversationId, LocalDateTime createdAt) {
		Long id = jdbcTemplate.queryForObject("""
				INSERT INTO question_log (user_id, user_question, sub_question, user_text, normalized_question, llm_question,
				                          conversation_id, created_at)
				VALUES (?, 'q', 'q', 'q', 'q', 'q', ?, ?)
				RETURNING id
				""", Long.class, userId, conversationId, createdAt);
		questionLogIds.add(id);
		return id;
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
				INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason)
				VALUES (?, ?, 'q', 'q', 'NO_FAQ')
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
