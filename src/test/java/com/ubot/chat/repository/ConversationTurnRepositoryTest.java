package com.ubot.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.ai.dto.ConversationTurn;
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
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("판정 LLM에 넘길 이전 대화 조회 테스트")
class ConversationTurnRepositoryTest {

	@Autowired
	private ConversationTurnRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final LocalDateTime base = LocalDateTime.now().minusHours(1);
	private Long userId;
	private Long conversationId;

	@BeforeEach
	void setUp() {
		userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (email, password_hash, name) VALUES (?, 'hash', '테스터') RETURNING user_id",
				Long.class, UUID.randomUUID() + "@test.com");
		conversationId = conversation();
	}

	@Test
	@DisplayName("같은 대화의 앞선 턴을 오래된 순으로 최대 개수만큼 원문과 함께 돌려주고, 성공한 턴은 하위 질문과 답변을, 실패한 턴은 원문을 하위 질문으로 쓴다")
	void returnsRecentTurnsOldestFirst() {
		Long oldest = attempt("가장 오래된 질문", "SUCCESS", 1, key(), 1, null);
		log(oldest, "가장 오래된 질문", "가장 오래된 질문", 0, "오래된 답변");
		attempt("해외 로밍 신청", "FAIL", 2, key(), 1, null);
		Long split = attempt("요금이랑 데이터 알려줘", "SUCCESS", 3, key(), 1, null);
		log(split, "요금이랑 데이터 알려줘", "5G 프리미엄 월 요금", 0, "요금 답변");
		log(split, "요금이랑 데이터 알려줘", "5G 프리미엄 데이터 제공량", 1, "요금 답변");
		// 하위 질문을 저장하기 전의 형식(sub_question 없음)은 원문으로 대신합니다.
		Long legacy = attempt("유심 재발급", "SUCCESS", 4, key(), 1, null);
		log(legacy, "유심 재발급", null, 0, "재발급 답변");
		Long current = attempt("그럼 비용은?", "PENDING", 5, key(), 1, null);

		List<ConversationTurn> turns = repository.findPreviousTurns(current, 3);

		assertThat(turns).containsExactly(
				new ConversationTurn("해외 로밍 신청", List.of("해외 로밍 신청"), null),
				// 원문은 사용자가 입력한 질문이고, 하위 질문은 질문 로그의 검색어입니다.
				new ConversationTurn("요금이랑 데이터 알려줘", List.of("5G 프리미엄 월 요금", "5G 프리미엄 데이터 제공량"), "요금 답변"),
				new ConversationTurn("유심 재발급", List.of("유심 재발급"), "재발급 답변"));
	}

	@Test
	@DisplayName("재시도한 턴은 마지막 시도의 결과를 쓰고, 처음 물은 시각 순서로 놓는다")
	void usesLatestAttemptOfRetriedTurn() {
		String retriedKey = key();
		attempt("유심 재발급", "FAIL", 1, retriedKey, 1, null);
		attempt("해외 로밍 신청", "FAIL", 2, key(), 1, null);
		Long retried = attempt("유심 재발급", "SUCCESS", 3, retriedKey, 2, null);
		log(retried, "유심 재발급", "유심 재발급", 0, "재발급 답변");
		Long current = attempt("그거 비용은?", "PENDING", 4, key(), 1, null);

		assertThat(repository.findPreviousTurns(current, 3)).containsExactly(
				new ConversationTurn("유심 재발급", List.of("유심 재발급"), "재발급 답변"),
				new ConversationTurn("해외 로밍 신청", List.of("해외 로밍 신청"), null));
	}

	@Test
	@DisplayName("재시도는 처음 물은 시각 이전의 턴만 보고, 자기 이전 시도는 이전 대화로 보지 않는다")
	void retrySeesTurnsBeforeFirstAsk() {
		String retriedKey = key();
		Long before = attempt("요금제 변경", "SUCCESS", 1, key(), 1, null);
		log(before, "요금제 변경", "요금제 변경", 0, "변경 답변");
		attempt("그거 위약금은?", "FAIL", 2, retriedKey, 1, null);
		attempt("해외 로밍 신청", "FAIL", 3, key(), 1, null);
		Long retry = attempt("그거 위약금은?", "PENDING", 4, retriedKey, 2, null);

		assertThat(repository.findPreviousTurns(retry, 3)).containsExactly(
				new ConversationTurn("요금제 변경", List.of("요금제 변경"), "변경 답변"));
	}

	@Test
	@DisplayName("의도 재검색 시도와 다른 대화의 턴은 이전 대화로 보지 않는다")
	void ignoresResearchAndOtherConversations() {
		Long source = attempt("유심 재발급", "SUCCESS", 1, key(), 1, null);
		log(source, "유심 재발급", "유심 재발급", 0, "원본 답변");
		Long research = attempt("유심 재발급", "SUCCESS", 2, key(), 1, source);
		log(research, "유심 재발급", "유심 재발급", 0, "재검색 답변");
		Long otherConversation = conversation();
		jdbcTemplate.update("""
				INSERT INTO answer_attempts_history (user_id, conversation_id, question, attempt_count, status,
				                                     idempotency_key, created_at)
				VALUES (?, ?, '다른 대화 질문', 1, 'FAIL', ?, ?)
				""", userId, otherConversation, key(), base.plusMinutes(3));
		Long current = attempt("그거 비용은?", "PENDING", 4, key(), 1, null);

		assertThat(repository.findPreviousTurns(current, 3)).containsExactly(
				new ConversationTurn("유심 재발급", List.of("유심 재발급"), "원본 답변"));
	}

	@Test
	@DisplayName("대화에 속하지 않은 시도는 이전 대화가 없다")
	void returnsEmptyWithoutConversation() {
		Long current = jdbcTemplate.queryForObject("""
				INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key)
				VALUES (?, '유심 재발급', 1, 'PENDING', ?)
				RETURNING attempt_id
				""", Long.class, userId, key());

		assertThat(repository.findPreviousTurns(current, 3)).isEmpty();
	}

	private Long conversation() {
		return jdbcTemplate.queryForObject(
				"INSERT INTO conversations (user_id, type) VALUES (?, 'MEMBER') RETURNING conversation_id",
				Long.class, userId);
	}

	private Long attempt(String question, String status, int minutes, String key, int attemptCount, Long sourceId) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO answer_attempts_history (user_id, conversation_id, question, attempt_count, status,
				                                     idempotency_key, created_at, source_attempt_id, intent)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING attempt_id
				""", Long.class, userId, conversationId, question, attemptCount, status, key,
				base.plusMinutes(minutes), sourceId, sourceId == null ? null : "GENERAL");
	}

	private void log(Long attemptId, String question, String subQuestion, int index, String answer) {
		jdbcTemplate.update("""
				INSERT INTO question_log (user_id, conversation_id, attempt_id, user_question, sub_question,
				                          sub_question_index, llm_question)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", userId, conversationId, attemptId, question, subQuestion, index, answer);
	}

	private String key() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
