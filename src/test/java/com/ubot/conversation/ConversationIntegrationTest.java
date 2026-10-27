package com.ubot.conversation;

import com.ubot.ai.service.AiGuardModelService;
import com.ubot.ai.service.AiGuardTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.support.ChatJobTestDriver;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.faq.service.FaqSearchMockSupport;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
		"prompt.faq.user-location=classpath:prompts/test-faq-user.txt",
		// LLM 호출 횟수를 검증하므로 판정 LLM은 끕니다. 판정은 SubQuestionChatIntegrationTest에서 확인합니다.
		"CHAT_SUB_QUESTION_ENABLED=false",
		"CHAT_TOP_K=3",
		"CHAT_CONFIDENCE_THRESHOLD=0.75",
		"CHAT_MAX_ATTEMPTS=3"})
@Import({ChatJobTestDriver.Config.class, PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("회원 대화방 통합 테스트")
class ConversationIntegrationTest {
    @MockitoBean AiGuardModelService guardModel;

	@Autowired MockMvc mvc;
	@Autowired JwtUtil jwt;
	@Autowired ObjectMapper objectMapper;
	@Autowired UserRepository users;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired QuestionLogRepository questions;
	@Autowired FaqLogRepository faqLogs;
	@Autowired UnansweredQuestionRepository unansweredQuestions;
	@Autowired UnansweredQuestionGroupRepository unansweredQuestionGroups;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired ChatJobTestDriver driver;
	@MockitoBean FaqVectorService vector;
	@MockitoBean FaqSentenceFilterService sentenceFilter;
	@MockitoBean LlmClient llm;

	User member;
	User other;

	@BeforeEach
	void setup() {
        AiGuardTestSupport.allow(guardModel);
		FaqSearchMockSupport.bridgeSearch(vector);
		FaqSearchMockSupport.passAnswers(sentenceFilter);
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:result:*"));
		redis.delete(redis.keys("chat:jobs:published:*"));
		unansweredQuestions.deleteAllInBatch();
		unansweredQuestionGroups.deleteAllInBatch();
		faqLogs.deleteAllInBatch();
		questions.deleteAllInBatch();
		attempts.deleteAllInBatch();
		jdbc.update("delete from conversations");
		member = saveUser();
		other = saveUser();
		Long category = jdbc.queryForObject(
				"insert into faq_category(name) values (?) returning id", Long.class, UUID.randomUUID().toString());
		Long faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
				Long.class, category, "유심 재발급", "매장 방문");
		when(vector.getSimilarList(anyString(), eq(3)))
				.thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
	}

	@Test
	@DisplayName("대화방 없이 질문하면 새 회원 대화방을 만들고, 받은 ID로 질문하면 같은 대화방에 이어 붙인다")
	void memberQuestionCreatesAndContinuesConversation() throws Exception {
		UUID conversationId = askAsMember(member, "첫 질문", null);

		assertThat(jdbc.queryForObject(
				"select type from conversations where public_id = ?", String.class, conversationId)).isEqualTo("MEMBER");

		assertThat(askAsMember(member, "두 번째 질문", conversationId)).isEqualTo(conversationId);
		assertThat(jdbc.queryForObject("select count(*) from conversations", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"select count(*) from question_log q join conversations c on c.conversation_id = q.conversation_id"
						+ " where c.public_id = ? and q.user_id = ?", Long.class, conversationId, member.getId()))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("다른 회원의 대화방이나 없는 대화방으로는 질문할 수 없다")
	void cannotContinueOthersConversation() throws Exception {
		UUID othersConversation = askAsMember(other, "다른 회원 질문", null);

		mvc.perform(question("끼어들기", othersConversation).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(question("없는 대화", UUID.randomUUID()).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject(
				"select count(*) from answer_attempts_history where user_id = ?", Long.class, member.getId()))
				.isZero();
	}

	@Test
	@DisplayName("내 대화방 목록은 최근 질문순이고, 제목은 첫 질문이며, 다른 회원 대화방은 보이지 않는다")
	void listsMyConversationsByRecentQuestion() throws Exception {
		UUID older = askAsMember(member, "요금제 문의", null);
		UUID newer = askAsMember(member, "매장 문의", null);
		askAsMember(other, "다른 회원 질문", null);
		askAsMember(member, "요금제 추가 질문", older);

		mvc.perform(get("/conversations/me").header("Authorization", bearer(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.content[0].conversationId").value(older.toString()))
				.andExpect(jsonPath("$.data.content[0].title").value("요금제 문의"))
				.andExpect(jsonPath("$.data.content[1].conversationId").value(newer.toString()))
				.andExpect(jsonPath("$.data.content[1].title").value("매장 문의"));

		mvc.perform(get("/conversations/me").param("size", "10").param("page", "1")
						.header("Authorization", bearer(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content").isEmpty());
		mvc.perform(get("/conversations/me").param("size", "7").header("Authorization", bearer(member)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/conversations/me")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("대화 내용은 질문·답변을 오래된 순으로 주고, 다른 회원의 대화방은 조회할 수 없다")
	void returnsMessagesOfMyConversationOnly() throws Exception {
		UUID conversationId = askAsMember(member, "첫 질문", null);
		askAsMember(member, "두 번째 질문", conversationId);

		mvc.perform(get("/conversations/{id}/messages", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].question").value("첫 질문"))
				.andExpect(jsonPath("$.data[0].answer").value("답변"))
				.andExpect(jsonPath("$.data[1].question").value("두 번째 질문"));

		mvc.perform(get("/conversations/{id}/messages", conversationId).header("Authorization", bearer(other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CONV-001"));
	}

	@Test
	@DisplayName("게스트 대화는 응답에 대화방 ID를 주고, 로그인 후 그 ID로 이어 질문하면 같은 대화방이 목록에 나온다")
	void guestConversationContinuesAfterLogin() throws Exception {
		MockHttpSession session = new MockHttpSession();
		UUID conversationId = complete(question("게스트 질문", null).session(session));
		assertThat(conversationId).isNotNull();

		assertThat(complete(question("로그인 후 질문", conversationId).session(session)
				.header("Authorization", bearer(member)))).isEqualTo(conversationId);

		mvc.perform(get("/conversations/me").header("Authorization", bearer(member)))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].conversationId").value(conversationId.toString()))
				.andExpect(jsonPath("$.data.content[0].title").value("게스트 질문"));
		mvc.perform(get("/conversations/{id}/messages", conversationId).header("Authorization", bearer(member)))
				.andExpect(jsonPath("$.data.length()").value(2));
	}

	@Test
	@DisplayName("답변에 실패해도 응답에 대화방 ID를 주고, 그 ID로 이어 질문하면 같은 대화방을 쓴다")
	void failedAnswerStillReturnsConversationId() throws Exception {
		when(vector.getSimilarList(anyString(), eq(3))).thenReturn(List.of());
		var submitted = mvc.perform(question("결과 없는 질문", null).header("Authorization", bearer(member)))
				.andExpect(status().isAccepted()).andReturn();
		driver.processAvailableJobs();
		String body = submitted.getResponse().getContentAsString(StandardCharsets.UTF_8);
		UUID conversationId = UUID.fromString(objectMapper.readTree(body).path("data").path("conversationId").asString());
		String jobId = objectMapper.readTree(body).path("data").path("jobId").asString();
		mvc.perform(get("/chat/jobs/{jobId}", jobId).header("Authorization", bearer(member)))
				.andExpect(jsonPath("$.data.status").value("FAILED"));

		assertThat(jdbc.queryForObject(
				"select count(*) from conversations where public_id = ?", Long.class, conversationId)).isEqualTo(1);
		mvc.perform(get("/conversations/me").header("Authorization", bearer(member)))
				.andExpect(jsonPath("$.data.totalElements").value(0));
		when(vector.getSimilarList(anyString(), eq(3))).thenReturn(List.of(
				new FaqSearchResponseDto(jdbc.queryForObject("select max(id) from faq", Long.class), "q", "a", 0.9, Intent.GENERAL)));
		assertThat(askAsMember(member, "다시 질문", conversationId)).isEqualTo(conversationId);
		assertThat(jdbc.queryForObject("select count(*) from conversations", Long.class)).isEqualTo(1);
		mvc.perform(get("/conversations/me").header("Authorization", bearer(member)))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].title").value("다시 질문"));
	}

	@Test
	@DisplayName("대화를 소프트 삭제하면 목록에서 제외하고 질문·답변·FAQ 로그·작업은 보존한다")
	void deletesConversationWithoutDeletingHistory() throws Exception {
		UUID deleted = askAsMember(member, "첫 질문", null);
		UUID remaining = askAsMember(member, "두 번째 대화", null);
		long questionCount = questions.count();
		long attemptCount = attempts.count();
		long faqLogCount = faqLogs.count();
		Long jobCount = jdbc.queryForObject("select count(*) from chat_jobs", Long.class);

		mvc.perform(delete("/conversations/{id}", deleted).header("Authorization", bearer(member)))
				.andExpect(status().isNoContent());

		LocalDateTime deletedAt = jdbc.queryForObject("select deleted_at from conversations where public_id = ?",
				LocalDateTime.class, deleted);
		assertThat(deletedAt).isNotNull();
		assertThat(questions.count()).isEqualTo(questionCount);
		assertThat(attempts.count()).isEqualTo(attemptCount);
		assertThat(faqLogs.count()).isEqualTo(faqLogCount).isPositive();
		assertThat(jdbc.queryForObject("select count(*) from chat_jobs", Long.class)).isEqualTo(jobCount);
		assertThat(jdbc.queryForObject("select count(*) from conversations", Long.class)).isEqualTo(2);
		mvc.perform(get("/conversations/me").header("Authorization", bearer(member)))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content.length()").value(1))
				.andExpect(jsonPath("$.data.content[0].conversationId").value(remaining.toString()));

		mvc.perform(delete("/conversations/{id}", deleted).header("Authorization", bearer(member)))
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select deleted_at from conversations where public_id = ?",
				LocalDateTime.class, deleted)).isEqualTo(deletedAt);
	}

	@Test
	@DisplayName("다른 회원의 대화나 없는 대화는 삭제할 수 없으며 인증 없이 삭제할 수 없다")
	void requiresConversationOwnershipToDelete() throws Exception {
		UUID conversationId = askAsMember(other, "다른 회원 질문", null);
		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(delete("/conversations/{id}", UUID.randomUUID()).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(delete("/conversations/{id}", conversationId)).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("select deleted_at from conversations where public_id = ?",
				LocalDateTime.class, conversationId)).isNull();
	}

	@Test
	@DisplayName("삭제된 대화의 메시지·작업 조회와 이어 질문·재시도·의도 재검색을 차단한다")
	void blocksAllUserAccessToDeletedConversation() throws Exception {
		JsonNode submitted = submitAsMember("첫 질문");
		UUID conversationId = UUID.fromString(submitted.path("conversationId").asString());
		String jobId = submitted.path("jobId").asString();
		String key = submitted.path("idempotencyKey").asString();
		driver.processAvailableJobs();
		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isNoContent());

		mvc.perform(get("/conversations/{id}/messages", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(question("추가 질문", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(post("/chat/questions/retries").header("Authorization", bearer(member))
				.header("Idempotency-Key", key))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(post("/chat/questions/research").header("Authorization", bearer(member))
				.header("Idempotency-Key", key).contentType("application/json").content("{\"intent\":\"GENERAL\"}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		mvc.perform(get("/chat/jobs/{id}", jobId).header("Authorization", bearer(member)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT-020"));
		assertThat(attempts.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("대기 중인 답변이 있으면 삭제를 거절하고 답변이 끝나면 삭제할 수 있다")
	void rejectsDeletionWhileAnswerIsQueued() throws Exception {
		JsonNode submitted = submitAsMember("첫 질문");
		UUID conversationId = UUID.fromString(submitted.path("conversationId").asString());
		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONV-002"));
		assertThat(jdbc.queryForObject("select deleted_at from conversations where public_id = ?",
				LocalDateTime.class, conversationId)).isNull();

		driver.processAvailableJobs();
		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("실행 중인 답변이 있으면 대화를 삭제할 수 없다")
	void rejectsDeletionWhileAnswerIsRunning() throws Exception {
		JsonNode submitted = submitAsMember("첫 질문");
		UUID conversationId = UUID.fromString(submitted.path("conversationId").asString());
		jdbc.update("update chat_jobs set execution_status = 'RUNNING', started_at = localtimestamp, "
				+ "heartbeat_at = localtimestamp where job_id = ?", submitted.path("jobId").asLong());
		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONV-002"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"WAITING", "ACTIVE", "PAUSED", "ENDED"})
	@DisplayName("사람 상담 세션이 있는 대화는 상태와 관계없이 삭제를 거절하고 목록과 이력을 유지한다")
	void rejectsDeletionOfConversationWithCounselSession(String sessionStatus) throws Exception {
		UUID conversationId = askAsMember(member, "상담 전 질문", null);
		User counselor = saveUser(UserRole.COUNSELOR);
		UUID sessionId = UUID.randomUUID();
		boolean hasMessage = !"WAITING".equals(sessionStatus);
		jdbc.update("""
				INSERT INTO counseling.sessions
				    (id, customer_id, counselor_id, status, conversation_id, last_delivered_seq, paused_at, ended_at)
				VALUES (?, ?, ?, ?, ?, ?,
				    CASE WHEN ? = 'PAUSED' THEN now() ELSE NULL END,
				    CASE WHEN ? = 'ENDED' THEN now() ELSE NULL END)
				""", sessionId, member.getId(), hasMessage ? counselor.getId() : null,
				sessionStatus, conversationId, hasMessage ? 1L : 0L, sessionStatus, sessionStatus);
		if (hasMessage) {
			jdbc.update("""
					INSERT INTO counseling.messages
					    (session_id, sender_id, sender_role, assignment_version, client_message_id,
					     content, status, delivery_sequence, decided_at)
					VALUES (?, ?, 'COUNSELOR', 0, ?, '상담사 답변', 'ALLOWED', 1, now())
					""", sessionId, counselor.getId(), UUID.randomUUID());
		}

		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONV-003"));
		mvc.perform(delete("/conversations/{id}", conversationId).header("Authorization", bearer(other)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONV-001"));
		assertThat(jdbc.queryForObject("select deleted_at from conversations where public_id = ?",
				LocalDateTime.class, conversationId)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from counseling.sessions where id = ?",
				Long.class, sessionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from counseling.messages where session_id = ?",
				Long.class, sessionId)).isEqualTo(hasMessage ? 1 : 0);
		mvc.perform(get("/conversations/me").header("Authorization", bearer(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].conversationId").value(conversationId.toString()))
				.andExpect(jsonPath("$.data.content[0].hasCounsel").value(true));
		mvc.perform(get("/conversations/{id}/messages", conversationId).header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
	}

	private JsonNode submitAsMember(String question) throws Exception {
		String body = mvc.perform(question(question, null).header("Authorization", bearer(member)))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return objectMapper.readTree(body).path("data");
	}

	private UUID askAsMember(User user, String question, UUID conversationId) throws Exception {
		return complete(question(question, conversationId).header("Authorization", bearer(user)));
	}

	private UUID complete(MockHttpServletRequestBuilder request) throws Exception {
		String body = mvc.perform(request).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		driver.processAvailableJobs();
		JsonNode conversationId = objectMapper.readTree(body).path("data").path("conversationId");
		return conversationId.isString() ? UUID.fromString(conversationId.asString()) : null;
	}

	private MockHttpServletRequestBuilder question(String question, UUID conversationId) {
		String body = conversationId == null
				? "{\"question\":\"" + question + "\"}"
				: "{\"question\":\"" + question + "\",\"conversationId\":\"" + conversationId + "\"}";
		return post("/chat/questions").contentType("application/json").content(body);
	}

	private String bearer(User target) {
		return "Bearer " + jwt.createAccessToken(target);
	}

	private User saveUser() {
		return saveUser(UserRole.USER);
	}

	private User saveUser(UserRole role) {
		return users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("대화방 테스트").role(role)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
	}
}
