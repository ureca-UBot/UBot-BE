package com.ubot.chat.controller;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.service.ChatService;
import com.ubot.common.ErrorCode;
import com.ubot.conversation.service.ConversationService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.guest.session.GuestSessionState;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
		"prompt.faq.user-location=classpath:prompts/test-faq-user.txt",
		"CHAT_TOP_K=3",
		"CHAT_CONFIDENCE_THRESHOLD=0.75",
		"CHAT_MAX_ATTEMPTS=3"})
@Import(PgvectorTestConfiguration.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("게스트 채팅 통합 테스트")
class GuestChatIntegrationTest {
	private static final String ANSWERABLE = "답변 가능한 질문";
	private static final String MISSING_FAQ = "FAQ에 없는 질문";

	@Autowired MockMvc mvc;
	@Autowired ChatService chatService;
	@Autowired ConversationService conversationService;
	@Autowired JwtUtil jwt;
	@Autowired UserRepository users;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired QuestionLogRepository questions;
	@Autowired FaqLogRepository faqLogs;
	@Autowired UnansweredQuestionRepository unansweredQuestions;
	@Autowired UnansweredQuestionGroupRepository unansweredQuestionGroups;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean FaqVectorService vector;
	@MockitoBean LlmClient llm;

	User user;
	User admin;
	Long faqId;

	@BeforeEach
	void setup() {
		unansweredQuestions.deleteAllInBatch();
		unansweredQuestionGroups.deleteAllInBatch();
		faqLogs.deleteAllInBatch();
		questions.deleteAllInBatch();
		attempts.deleteAllInBatch();
		jdbc.update("delete from conversations");
		jdbc.update("update guest_chat_settings set max_question_count = 5, updated_by = null where id = 1");
		user = saveUser(UserRole.USER);
		admin = saveUser(UserRole.ADMIN);
		Long category = jdbc.queryForObject(
				"insert into faq_category(name) values (?) returning id", Long.class, UUID.randomUUID().toString());
		faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
				Long.class, category, "유심 재발급", "매장 방문");
		when(vector.getSimilarList(anyString(), eq(3)))
				.thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(vector.getSimilarList(MISSING_FAQ, 3)).thenReturn(List.of());
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("게스트 답변"));
	}

	@Test
	@DisplayName("최초 요청은 세션과 Conversation을 만들고, 같은 세션은 재사용하며, 다른 세션과 만료된 세션은 새로 만든다")
	void sessionLifecycleCreatesAndReusesConversation() throws Exception {
		var first = mvc.perform(question(ANSWERABLE)).andExpect(request().asyncStarted()).andReturn();
		MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
		assertThat(session).as("최초 게스트 요청에서 세션 생성").isNotNull();
		assertThat(session.getMaxInactiveInterval()).isEqualTo(30 * 60);
		first.getAsyncResult(10_000);
		mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
		assertThat(conversationCount()).isEqualTo(1);

		complete(question(ANSWERABLE).session(session), null);
		assertThat(conversationCount()).as("같은 세션은 Conversation을 추가로 만들지 않음").isEqualTo(1);
		assertThat(attempts.findAll()).hasSize(2)
				.extracting(AnswerAttemptsHistory::getConversationId).containsOnly(conversationId(session));

		complete(question(ANSWERABLE).session(new MockHttpSession()), null);
		assertThat(conversationCount()).as("다른 세션은 새 Conversation").isEqualTo(2);

		Long expiredConversationId = conversationId(session);
		session.invalidate();
		var afterExpiry = mvc.perform(question(ANSWERABLE).session(session))
				.andExpect(request().asyncStarted()).andReturn();
		afterExpiry.getAsyncResult(10_000);
		mvc.perform(asyncDispatch(afterExpiry)).andExpect(status().isOk());
		assertThat(conversationCount()).as("만료된 세션은 이전 Conversation을 재사용하지 않음").isEqualTo(3);
		assertThat(attempts.findAll()).extracting(AnswerAttemptsHistory::getConversationId)
				.filteredOn(expiredConversationId::equals).hasSize(2);
	}

	@Test
	@DisplayName("다른 게스트의 멱등키로는 재시도할 수 없다")
	void guestCannotRetryAnotherGuestsQuestion() throws Exception {
		doThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT)).when(llm).generateAnswer(any());
		var guestA = new MockHttpSession();
		var guestB = new MockHttpSession();
		String key = keyFrom(complete(question(ANSWERABLE).session(guestA), LlmErrorCode.LLM_TIMEOUT));
		complete(question(ANSWERABLE).session(guestB), LlmErrorCode.LLM_TIMEOUT);

		mvc.perform(retry(key).session(guestB))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
		assertThat(attempts.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("정상 답변과 FAQ 미발견만 질문 횟수를 쓰고, 시스템 오류와 재시도는 쓰지 않는다")
	void quotaCountsOnlyAnswersAndMissingFaq() throws Exception {
		jdbc.update("update guest_chat_settings set max_question_count = 2 where id = 1");
		var session = new MockHttpSession();

		doThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT)).when(llm).generateAnswer(any());
		String systemFailureKey = keyFrom(complete(question(ANSWERABLE).session(session), LlmErrorCode.LLM_TIMEOUT));
		doReturn(new LlmResponseDto("게스트 답변")).when(llm).generateAnswer(any());

		String missingFaqKey = keyFrom(complete(question(MISSING_FAQ).session(session), ChatErrorCode.NO_FAQ));
		complete(question(ANSWERABLE).session(session), null);

		mvc.perform(question(ANSWERABLE).session(session))
				.andExpect(status().isTooManyRequests())
				.andExpect(request().asyncNotStarted())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED.getCode()));

		assertThat(complete(retry(systemFailureKey).session(session), null))
				.contains("\"attemptCount\":2", "\"status\":\"SUCCESS\"");
		assertThat(complete(retry(missingFaqKey).session(session), ChatErrorCode.NO_FAQ))
				.contains("\"attemptCount\":2");

		mvc.perform(question(ANSWERABLE).session(session))
				.andExpect(status().isTooManyRequests());
		assertThat(attempts.findAll()).filteredOn(attempt -> attempt.getAttemptCount() == 1).hasSize(3);
	}

	@Test
	@DisplayName("동시에 보낸 신규 질문도 최대 질문 횟수를 넘지 못한다")
	void concurrentQuestionsCannotExceedQuota() throws Exception {
		int maxQuestionCount = 5;
		int requestCount = maxQuestionCount + 1;
		Long conversationId = conversationService.createGuestConversation();
		CountDownLatch release = new CountDownLatch(1);
		when(llm.generateAnswer(any())).thenAnswer(call -> {
			if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
			return new LlmResponseDto("게스트 답변");
		});
		var barrier = new CyclicBarrier(requestCount);
		List<String> results = new ArrayList<>();
		try (var callers = Executors.newFixedThreadPool(requestCount)) {
			List<Future<String>> futures = new ArrayList<>();
			for (int index = 0; index < requestCount; index++) {
				String questionText = "동시 질문 " + index;
				Callable<String> call = () -> {
					barrier.await(5, TimeUnit.SECONDS);
					try {
						chatService.createGuestChat(conversationId, questionText, null);
						return "ACCEPTED";
					} catch (ChatException exception) {
						return exception.getErrorCode().getCode();
					}
				};
				futures.add(callers.submit(call));
			}
			for (Future<String> future : futures) {
				results.add(future.get(10, TimeUnit.SECONDS));
			}
			assertThat(results).filteredOn("ACCEPTED"::equals).hasSize(maxQuestionCount);
			assertThat(results).filteredOn(ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED.getCode()::equals).hasSize(1);
			assertThat(attempts.count()).isEqualTo(maxQuestionCount);
		} finally {
			release.countDown();
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline
				&& attempts.findAll().stream().anyMatch(attempt -> "PENDING".equals(attempt.getStatus()))) {
			Thread.sleep(20);
		}
		assertThat(attempts.findAll()).allMatch(attempt -> "SUCCESS".equals(attempt.getStatus()));
	}

	@Test
	@DisplayName("관리자만 게스트 최대 질문 횟수를 조회·변경할 수 있고, 변경은 바로 적용된다")
	void adminUpdatesGuestQuestionLimit() throws Exception {
		mvc.perform(get("/admin/guest-chat-settings")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/guest-chat-settings").header("Authorization", bearer(user)))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/admin/guest-chat-settings").header("Authorization", bearer(user))
						.contentType("application/json").content("{\"maxQuestionCount\":1}"))
				.andExpect(status().isForbidden());

		mvc.perform(get("/admin/guest-chat-settings").header("Authorization", bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.maxQuestionCount").value(5));

		for (String invalid : List.of("{\"maxQuestionCount\":0}", "{}")) {
			mvc.perform(patch("/admin/guest-chat-settings").header("Authorization", bearer(admin))
							.contentType("application/json").content(invalid))
					.andExpect(status().isBadRequest());
		}

		mvc.perform(patch("/admin/guest-chat-settings").header("Authorization", bearer(admin))
						.contentType("application/json").content("{\"maxQuestionCount\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.maxQuestionCount").value(1))
				.andExpect(jsonPath("$.data.updatedBy").value(admin.getId()))
				.andExpect(jsonPath("$.data.updatedAt").isString());
		assertThat(jdbc.queryForObject(
				"select max_question_count from guest_chat_settings where id = 1", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"select updated_by from guest_chat_settings where id = 1", Long.class)).isEqualTo(admin.getId());

		var session = new MockHttpSession();
		complete(question(ANSWERABLE).session(session), null);
		mvc.perform(question(ANSWERABLE).session(session))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED.getCode()));
	}

	@Test
	@DisplayName("로그인 후 첫 채팅 요청에서 게스트 Conversation과 기록을 회원에게 승계한다")
	void firstMemberRequestClaimsGuestConversation() throws Exception {
		var session = new MockHttpSession();
		complete(question(ANSWERABLE).session(session), null);
		doThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT)).when(llm).generateAnswer(any());
		String failedKey = keyFrom(complete(question(ANSWERABLE).session(session), LlmErrorCode.LLM_TIMEOUT));
		doReturn(new LlmResponseDto("회원 답변")).when(llm).generateAnswer(any());
		Long conversationId = conversationId(session);

		complete(question(ANSWERABLE).session(session).header("Authorization", bearer(user)), null);

		assertThat(session.isInvalid()).as("승계 후 게스트 세션 제거").isTrue();
		assertThat(jdbc.queryForMap("select type, user_id from conversations where conversation_id = ?", conversationId))
				.containsEntry("type", "MEMBER").containsEntry("user_id", user.getId());
		assertThat(attempts.findAll()).hasSize(3).allSatisfy(
				attempt -> assertThat(attempt.getUserId()).isEqualTo(user.getId()));
		assertThat(questions.findAll()).hasSize(2).allSatisfy(
				log -> assertThat(log.getUserId()).isEqualTo(user.getId()));
		assertThat(conversationCount()).isEqualTo(1);

		assertThat(complete(retry(failedKey).header("Authorization", bearer(user)), null))
				.contains("회원 답변", "\"attemptCount\":2", failedKey);

		User otherUser = saveUser(UserRole.USER);
		assertThat(conversationService.claimGuestConversation(conversationId, otherUser.getId())).isFalse();
		assertThat(jdbc.queryForObject("select user_id from conversations where conversation_id = ?",
				Long.class, conversationId)).isEqualTo(user.getId());

		complete(question(ANSWERABLE).session(session), null);
		assertThat(conversationCount()).as("승계 뒤의 게스트 요청은 새 Conversation").isEqualTo(2);
	}

	private MockHttpServletRequestBuilder question(String question) {
		return post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"" + question + "\"}");
	}

	private MockHttpServletRequestBuilder retry(String idempotencyKey) {
		return post("/chat/questions/retries").header("Idempotency-Key", idempotencyKey);
	}

	private String complete(MockHttpServletRequestBuilder request, ErrorCode expectedError) throws Exception {
		var pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
		pending.getAsyncResult(10_000);
		return mvc.perform(asyncDispatch(pending))
				.andExpect(status().is(expectedError == null ? 200 : expectedError.getStatus().value()))
				.andExpect(jsonPath("$.code").value(expectedError == null ? "SUCCESS" : expectedError.getCode()))
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private String keyFrom(String body) {
		var matcher = Pattern.compile("\"idempotencyKey\":\"([0-9a-f]{64})\"").matcher(body);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private Long conversationId(MockHttpSession session) {
		return ((GuestSessionState) session.getAttribute("guestSessionState")).getConversationId();
	}

	private long conversationCount() {
		return jdbc.queryForObject("select count(*) from conversations", Long.class);
	}

	private String bearer(User target) {
		return "Bearer " + jwt.createAccessToken(target);
	}

	private User saveUser(UserRole role) {
		return users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("게스트 테스트").role(role)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
	}
}
