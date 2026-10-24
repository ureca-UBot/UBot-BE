package com.ubot.chat.controller;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatSubmissionService.SubmissionType;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.support.ChatJobTestDriver;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.service.ChatService;
import com.ubot.common.ErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.faq.service.FaqSearchMockSupport;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 인증 필터·기존 Controller/Service/Prompt/LLM 서비스·JPA·임시 PostgreSQL을 연결합니다.
 * FAQ 검색의 반환값과 외부 LLM 통신만 mock이며 개발 DB와 실제 LLM은 사용하지 않습니다.
 */
// 질문은 202로 접수되고 테스트가 Worker를 직접 돌린 뒤 상태 조회로 확인함
@SpringBootTest(properties = {
		"prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
		"prompt.faq.user-location=classpath:prompts/test-faq-user.txt",
		// LLM 호출 횟수를 검증하므로 판정 LLM은 끕니다. 판정은 SubQuestionChatIntegrationTest에서 확인합니다.
		"CHAT_SUB_QUESTION_ENABLED=false",
		"CHAT_TOP_K=3",
		"CHAT_CONFIDENCE_THRESHOLD=0.75",
		"CHAT_MAX_ATTEMPTS=5"})
@Import({ChatJobTestDriver.Config.class, PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatLlmIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired ChatService service;
	@Autowired ChatJobTestDriver driver;
	@Autowired JwtUtil jwt;
	@Autowired UserRepository users;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired QuestionLogRepository questions;
	@Autowired FaqLogRepository faqLogs;
	@Autowired UnansweredQuestionRepository unansweredQuestions;
	@Autowired UnansweredQuestionGroupRepository unansweredQuestionGroups;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired ChatJobStream stream;
	@Autowired ChatJobRepository jobs;
	@Autowired ChatJobQueueRepository jobQueue;
	@Autowired com.ubot.chat.job.service.ChatJobCompletionService completion;
	@Autowired com.ubot.chat.job.service.ChatJobQueryService query;
	@Autowired com.ubot.conversation.service.ConversationService conversations;
	@Autowired com.ubot.chat.job.service.ChatSubmissionService submissions;
	@Autowired com.ubot.chat.service.ChatAttemptsService attemptsService;
	@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
	@MockitoBean FaqVectorService vector;
	@MockitoBean FaqSentenceFilterService sentenceFilter;
	@MockitoBean LlmClient llm;
	@Value("${CHAT_MAX_ATTEMPTS}") int maxAttempts;

	User user;
	String authorization;
	Long faqId;
	ChatJobTestFlow flow;

	@BeforeEach void setup() {
		FaqSearchMockSupport.bridgeSearch(vector);
		FaqSearchMockSupport.passAnswers(sentenceFilter);
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:result:*"));
		redis.delete(redis.keys("chat:jobs:published:*"));
		flow = new ChatJobTestFlow(mvc, driver);
		unansweredQuestions.deleteAllInBatch();
		unansweredQuestionGroups.deleteAllInBatch();
		faqLogs.deleteAllInBatch();
		questions.deleteAllInBatch();
		attempts.deleteAllInBatch();
		user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("채팅 테스트").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		authorization = "Bearer " + jwt.createAccessToken(user);
		Long category = jdbc.queryForObject(
				"insert into faq_category(name) values (?) returning id", Long.class, UUID.randomUUID().toString());
		faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
				Long.class, category, "유심 재발급", "매장 방문");
	}

	@Test void progressIsVisibleDuringSearchAndGenerationAndClearedOnCompletion() throws Exception {
		var submitted = mvc.perform(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"진행 확인\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.phase").value("WAITING"))
				.andExpect(jsonPath("$.data.phaseUpdatedAt").isString())
				.andExpect(jsonPath("$.data.deadlineAt").isString())
				.andExpect(jsonPath("$.data.serverTime").isString()).andReturn();
		long id = ChatJobTestFlow.jobId(submitted);
		var expectedDeadline = new java.util.concurrent.atomic.AtomicReference<java.time.Instant>();
		when(vector.getSimilarList("진행 확인", 3)).thenAnswer(call -> {
			var state = query.getStatus(id, user.getId(), java.util.Optional.empty());
			assertThat(state.phase()).isEqualTo(com.ubot.ai.progress.AnswerProgress.Phase.SEARCHING);
			expectedDeadline.set(state.deadlineAt());
			return List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL));
		});
		when(llm.generateAnswer(any())).thenAnswer(call -> {
			var state = query.getStatus(id, user.getId(), java.util.Optional.empty());
			assertThat(state.phase()).isEqualTo(com.ubot.ai.progress.AnswerProgress.Phase.GENERATING);
			assertThat(state.deadlineAt()).isEqualTo(expectedDeadline.get());
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new LlmResponseDto("완료");
		});
		driver.processAvailableJobs();
		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.data.phase").isEmpty())
				.andExpect(jsonPath("$.data.deadlineAt").isEmpty());
		assertThat(expectedDeadline.get()).isNotNull();
	}

	@Test void successfulNewQuestionReturnsFinalJsonAfterRealJpaWrites() throws Exception {
		when(vector.getSimilarList("유심 재발급", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(faqId, "재발급 방법", "매장 방문", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("모의 LLM이 생성한 답변"));

		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"유심 재발급\",\"userId\":999}"));
		assertThat(body).contains("모의 LLM이 생성한 답변", "\"status\":\"SUCCEEDED\"", "\"success\":true", "\"attemptCount\":1")
				.doesNotContain("event:", "data:");
		var stored = questions.findAll().getFirst();
		assertThat(stored.getUserId()).isEqualTo(user.getId());
		assertThat(stored.getAnswer()).isEqualTo("모의 LLM이 생성한 답변");
		assertThat(faqLogs.findAll()).hasSize(1).first().satisfies(
				log -> assertThat(log.getQuestionLogId()).isEqualTo(stored.getId()));
		assertThat(attempts.findAll()).hasSize(1).first().satisfies(
				attempt -> assertThat(attempt.getStatus()).isEqualTo("SUCCESS"));
		assertThat(jdbc.queryForMap("select execution_status, finished_at is not null as finished from chat_jobs"))
				.containsEntry("execution_status", "SUCCEEDED")
				.containsEntry("finished", true);
		Long jobId = jdbc.queryForObject("select job_id from chat_jobs", Long.class);
		assertThat(redis.opsForValue().get("chat:jobs:result:" + jobId)).as("결과는 Redis에 질문 로그 ID로 남음")
				.contains("\"questionLogId\":" + stored.getId());
		assertThat(pendingMessages()).as("기록이 끝난 뒤 ACK").isZero();
		var request = ArgumentCaptor.forClass(LlmRequestDto.class);
		verify(llm).generateAnswer(request.capture());
		assertThat(request.getValue().messages().getLast().content())
				.contains("유심 재발급", "[FAQ ID: " + faqId + "]", "매장 방문");
	}

	@Test void questionIsOnlyQueuedUntilWorkerRuns() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));

		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("QUEUED"))
				.andExpect(jsonPath("$.data.answer").doesNotExist())
				.andExpect(jsonPath("$.data.retryable").value(false));
		assertThat(attempts.findAll()).singleElement()
				.satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo("PENDING"));
		assertThat(questions.count()).isZero();
		verifyNoInteractions(vector);
		verify(llm, never()).generateAnswer(any());

		assertThat(driver.processAvailableJobs()).isEqualTo(1);
		mvc.perform(flow.statusOf(submitted)).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
	}

	@Test void modelCallsRunWithoutAnOpenDatabaseTransaction() throws Exception {
		var transactionDuringSearch = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		var transactionDuringLlm = new java.util.concurrent.atomic.AtomicReference<Boolean>();
		when(vector.getSimilarList("질문", 3)).thenAnswer(call -> {
			transactionDuringSearch.set(TransactionSynchronizationManager.isActualTransactionActive());
			return List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL));
		});
		when(llm.generateAnswer(any())).thenAnswer(call -> {
			transactionDuringLlm.set(TransactionSynchronizationManager.isActualTransactionActive());
			return new LlmResponseDto("답변");
		});

		complete(post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}"));

		assertThat(transactionDuringSearch.get()).as("임베딩·검색 중 트랜잭션 없음").isFalse();
		assertThat(transactionDuringLlm.get()).as("LLM 호출 중 트랜잭션 없음").isFalse();
	}

	@Test void configuredAttemptLimitAllowsMoreThanThreeAttemptsAndBlocksNext() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), LlmErrorCode.LLM_TIMEOUT);
		assertThat(body).contains("\"status\":\"FAILED\"", "\"retryable\":true");
		String key = keyFrom(body);
		for (int count = 2; count <= maxAttempts; count++) {
			body = complete(post("/chat/questions/retries").header("Idempotency-Key", key), LlmErrorCode.LLM_TIMEOUT);
			assertThat(body).contains("\"attemptCount\":" + count, key);
		}
		assertThat(body).contains("\"retryable\":false");
		mvc.perform(post("/chat/questions/retries").header("Authorization", authorization)
				.header("Idempotency-Key", key)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.LIMIT_REACHED.getCode()));
		assertThat(attempts.findAll()).hasSize(maxAttempts).allMatch(
				a -> a.getIdempotencyKey().equals(key) && "FAIL".equals(a.getStatus()));
		assertThat(jdbc.queryForObject("select count(*) from chat_jobs where execution_status = 'FAILED'", Integer.class))
				.as("재시도마다 새 작업").isEqualTo(maxAttempts);
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		verify(llm, times(maxAttempts)).generateAnswer(any());
	}

	@ParameterizedTest
	@EnumSource(value = ChatErrorCode.class, names = {"NO_FAQ", "INSUFFICIENT_FAQ"})
	void evidenceFailureIsNotRetriedBecauseSameQuestionGivesSameResult(ChatErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(code == ChatErrorCode.NO_FAQ
				? List.of() : List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.74, Intent.GENERAL)));
		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), code);
		assertThat(body).contains("\"status\":\"FAILED\"", "\"retryable\":false", code.getMessage());
		String key = keyFrom(body);
		mvc.perform(post("/chat/questions/retries").header("Authorization", authorization)
				.header("Idempotency-Key", key)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.RETRY_NOT_ALLOWED.getCode()));
		assertThat(attempts.findAll()).hasSize(1).allSatisfy(attempt -> {
			assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
			assertThat(attempt.getStatus()).isEqualTo("FAIL");
			assertThat(attempt.getErrorCode()).isEqualTo(code);
		});
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		verify(llm, never()).generateAnswer(any());
	}

	@Test void failedFaqInsertRollsBackSuccessLogsAndRecordsStorageFailure() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(-999L, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("저장되지 않아야 하는 답변"));
		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), ChatErrorCode.STORAGE_UNAVAILABLE);
		assertThat(body).contains("\"status\":\"FAILED\"")
				.doesNotContain("\"status\":\"SUCCEEDED\"", "foreign key", "저장되지 않아야 하는 답변");
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		assertThat(attempts.findAll().getFirst().getStatus()).isEqualTo("FAIL");
		verify(llm, times(1)).generateAnswer(any());
	}

	@Test void guestQuestionsReuseSessionConversationAndStopAtConfiguredLimit() throws Exception {
		int maxQuestionCount = jdbc.queryForObject(
				"select max_question_count from guest_chat_settings where id = 1", Integer.class);
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("게스트 답변"));
		var session = new MockHttpSession();

		for (int count = 1; count <= maxQuestionCount; count++) {
			assertThat(completeGuest(post("/chat/questions").contentType("application/json")
					.content("{\"question\":\"질문\"}"), session)).contains("게스트 답변", "\"status\":\"SUCCEEDED\"");
		}
		mvc.perform(post("/chat/questions").session(session).contentType("application/json")
						.content("{\"question\":\"질문\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED.getCode()));

		assertThat(attempts.findAll()).hasSize(maxQuestionCount).allSatisfy(attempt -> {
			assertThat(attempt.getUserId()).isNull();
			assertThat(attempt.getConversationId()).isNotNull();
		});
		Long conversationId = attempts.findAll().getFirst().getConversationId();
		assertThat(questions.findAll()).hasSize(maxQuestionCount).allSatisfy(log -> {
			assertThat(log.getUserId()).isNull();
			assertThat(log.getConversationId()).isEqualTo(conversationId);
		});
		assertThat(jdbc.queryForObject("select type from conversations where conversation_id = ?",
				String.class, conversationId)).isEqualTo("GUEST");

		completeGuest(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), new MockHttpSession());
		assertThat(attempts.findAll()).extracting(AnswerAttemptsHistory::getConversationId)
				.filteredOn(id -> !id.equals(conversationId)).hasSize(1);
	}

	@Test void guestRetryIsLimitedToOwnSessionConversation() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
		var session = new MockHttpSession();
		String key = keyFrom(completeGuest(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), session, LlmErrorCode.LLM_TIMEOUT));

		var withoutSession = mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", key))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode())).andReturn();
		assertThat(withoutSession.getRequest().getSession(false)).as("재시도는 세션을 만들지 않음").isNull();
		var otherSession = new MockHttpSession();
		completeGuest(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), otherSession, LlmErrorCode.LLM_TIMEOUT);
		mvc.perform(post("/chat/questions/retries").session(otherSession).header("Idempotency-Key", key))
				.andExpect(status().isNotFound());
		mvc.perform(post("/chat/questions/retries").header("Authorization", authorization)
				.header("Idempotency-Key", key)).andExpect(status().isNotFound());

		doReturn(new LlmResponseDto("재시도 답변")).when(llm).generateAnswer(any());
		String retried = completeGuest(post("/chat/questions/retries").header("Idempotency-Key", key), session);
		assertThat(retried).contains("재시도 답변", "\"attemptCount\":2", key);
		assertThat(questions.findAll()).hasSize(1).first().satisfies(log -> assertThat(log.getUserId()).isNull());
	}

	@Test void jobStatusIsVisibleOnlyToItsOwner() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var memberJob = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		var guestSession = new MockHttpSession();
		var guestJob = flow.submit(post("/chat/questions").session(guestSession)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		driver.processAvailableJobs();
		User otherUser = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("다른 사용자").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		String otherAuthorization = "Bearer " + jwt.createAccessToken(otherUser);

		mvc.perform(get("/chat/jobs/{jobId}", ChatJobTestFlow.jobId(memberJob)).header("Authorization", otherAuthorization))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.JOB_NOT_FOUND.getCode()));
		mvc.perform(get("/chat/jobs/{jobId}", ChatJobTestFlow.jobId(memberJob)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/chat/jobs/{jobId}", ChatJobTestFlow.jobId(guestJob)).session(new MockHttpSession()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/chat/jobs/{jobId}", ChatJobTestFlow.jobId(guestJob)).header("Authorization", authorization))
				.andExpect(status().isNotFound());
		mvc.perform(get("/chat/jobs/{jobId}", Long.MAX_VALUE).header("Authorization", authorization))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ChatErrorCode.JOB_NOT_FOUND.getCode()));
		mvc.perform(get("/chat/jobs/{jobId}", ChatJobTestFlow.jobId(guestJob)).session(guestSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
	}

	@Test void invalidTokenIsNotTreatedAsGuest() throws Exception {
		mvc.perform(post("/chat/questions").header("Authorization", "Bearer invalid")
						.contentType("application/json").content("{\"question\":\"질문\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false));
		assertThat(attempts.count()).isZero();
		verifyNoInteractions(vector, llm);
	}

	@Test void unknownRetryKeyIsRejectedWithoutStartingGeneration() throws Exception {
		mvc.perform(post("/chat/questions/retries").header("Authorization", authorization)
				.header("Idempotency-Key", "b".repeat(64)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
		assertThat(attempts.count()).isZero();
		verifyNoInteractions(vector, llm);
	}

	@Test void anotherUsersRetryKeyIsRejectedWithoutStartingGeneration() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"), LlmErrorCode.LLM_TIMEOUT);
		assertThat(body).contains("\"retryable\":true");
		String key = keyFrom(body);
		User otherUser = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("다른 사용자").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		String otherAuthorization = "Bearer " + jwt.createAccessToken(otherUser);

		mvc.perform(post("/chat/questions/retries").header("Authorization", otherAuthorization)
				.header("Idempotency-Key", key))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
		assertThat(attempts.findAll()).hasSize(1).first().satisfies(attempt -> {
			assertThat(attempt.getUserId()).isEqualTo(user.getId());
			assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
			assertThat(attempt.getAttemptCount()).isEqualTo(1);
			assertThat(attempt.getStatus()).isEqualTo("FAIL");
		});
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		verify(vector, times(1)).getSimilarList("질문", 3);
		verify(llm, times(1)).generateAnswer(any());
	}

	@Test void concurrentRetryCreatesOnlyOneNewAttemptUnderJpaLock() throws Exception {
		String key = "c".repeat(64);
		var first = new AnswerAttemptsHistory(user.getId(), "질문", 1, key, LocalDateTime.now(), "", "");
		first.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
		attempts.saveAndFlush(first);
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var barrier = new CyclicBarrier(2);
		try (var callers = Executors.newFixedThreadPool(2)) {
			Callable<String> retry = () -> {
				barrier.await(5, TimeUnit.SECONDS);
				try { service.retryChat(user.getId(), key, null); return "ACCEPTED"; }
				catch (ChatException exception) { return exception.getErrorCode().getCode(); }
			};
			var a = callers.submit(retry);
			var b = callers.submit(retry);
			assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder("ACCEPTED", ChatErrorCode.PROCESSING.getCode());
			assertThat(attempts.count()).isEqualTo(2);
		}
		assertThat(jdbc.queryForObject("select count(*) from chat_jobs", Integer.class)).isEqualTo(1);
		assertThat(driver.processAvailableJobs()).isEqualTo(1);
		var latest = attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(user.getId(), key).orElseThrow();
		assertThat(latest.getStatus()).isEqualTo("SUCCESS");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void queuedJobPastMaxWaitFailsWithTimeoutAndCanBeRetried(boolean retry) throws Exception {
		String key = "d".repeat(64);
		AnswerAttemptsHistory initialAttempt = null;
		if (retry) {
			initialAttempt = new AnswerAttemptsHistory(user.getId(), "질문", 1, key, LocalDateTime.now(), "", "");
			initialAttempt.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
			initialAttempt = attempts.saveAndFlush(initialAttempt);
		}
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("재시도 답변"));
		var request = retry ? post("/chat/questions/retries").header("Idempotency-Key", key)
				: post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}");
		var submitted = flow.submit(request.header("Authorization", authorization));
		// Worker가 못 가져간 채 대기 한도를 넘긴 작업
		jdbc.update("update chat_jobs set deadline_at = CURRENT_TIMESTAMP - interval '1 hour'");

		driver.recover();

		String body = mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("FAILED"))
				.andExpect(jsonPath("$.data.errorCode").value("CHAT-016"))
				.andExpect(jsonPath("$.data.errorMessage").value("답변 생성 시간이 초과되었습니다."))
				.andExpect(jsonPath("$.data.attemptCount").value(retry ? 2 : 1))
				.andExpect(jsonPath("$.data.retryable").value(true))
				.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		var stored = attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(user.getId(), keyFrom(body))
				.orElseThrow();
		assertThat(stored.getStatus()).isEqualTo("FAIL");
		assertThat(jdbc.queryForObject("select error_code from answer_attempts_history where attempt_id = ?",
				String.class, stored.getId())).isEqualTo("CHAT-016");
		if (retry) {
			var initial = attempts.findById(initialAttempt.getId()).orElseThrow();
			assertThat(initial.getErrorCode()).isEqualTo(ChatErrorCode.VECTOR_SEARCH_FAILED);
		}
		assertThat(driver.processAvailableJobs()).as("남은 메시지는 끝난 작업이라 실행하지 않고 ACK만 함").isEqualTo(1);
		verify(llm, never()).generateAnswer(any());

		String retried = complete(post("/chat/questions/retries").header("Idempotency-Key", keyFrom(body)));
		assertThat(retried).contains("\"status\":\"SUCCEEDED\"", "\"attemptCount\":" + (retry ? 3 : 2));
	}

	@Test void duplicateMessageForSameJobCallsLlmOnce() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		// 재발행 등으로 같은 작업의 메시지가 한 번 더 온 상황
		stream.publish(new ChatJobMessage(ChatJobTestFlow.jobId(submitted), new ChatRequestContext(null, null, null)));

		assertThat(driver.processAvailableJobs()).isEqualTo(2);

		verify(llm, times(1)).generateAnswer(any());
		assertThat(questions.count()).as("답변 기록은 한 번").isEqualTo(1);
		assertThat(pendingMessages()).as("두 메시지 모두 ACK").isZero();
		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.data.answer").value("답변"));
	}

	@Test void jobCommittedWithoutMessageIsRepublishedAndAnswered() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		// API가 커밋 직후 발행 못 하고 죽음. 작업은 있고 메시지는 없음
		var attempt = attempts.saveAndFlush(new AnswerAttemptsHistory(user.getId(), "질문", 1, "e".repeat(64),
				LocalDateTime.now(), "", ""));
		ChatJob job = jobs.saveAndFlush(ChatJob.queue(attempt.getId()));
		jdbc.update("update chat_jobs set created_at = created_at - interval '1 minute'");
		assertThat(driver.processAvailableJobs()).isZero();

		driver.recover();

		assertThat(driver.processAvailableJobs()).isEqualTo(1);
		mvc.perform(get("/chat/jobs/{jobId}", job.getId()).header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.data.answer").value("답변"));
	}

	@Test void queuedJobsAreRepublishedAfterRedisDataLoss() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		// Redis가 영속화 없이 재시작해 스트림·발행 표시가 모두 사라짐
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:published:*"));
		jdbc.update("update chat_jobs set created_at = created_at - interval '1 minute'");

		driver.recover();

		assertThat(driver.processAvailableJobs()).isEqualTo(1);
		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
		verify(llm, times(1)).generateAnswer(any());
	}

	@Test void publishedQueuedJobIsNotRepublished() throws Exception {
		flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		jdbc.update("update chat_jobs set created_at = created_at - interval '1 minute'");

		driver.recover();

		assertThat(redis.opsForStream().size(ChatJobStream.STREAM_KEY)).as("줄 서 있는 메시지만 있음").isEqualTo(1);
	}

	@Test void messageLeftByKilledWorkerIsReclaimedAndAnsweredOnce() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		leftByKilledWorker(ChatJobTestFlow.jobId(submitted), 1);

		driver.recover();

		verify(llm, times(1)).generateAnswer(any());
		assertThat(pendingMessages()).as("넘겨받은 메시지도 ACK").isZero();
		assertThat(questions.count()).isEqualTo(1);
		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.data.answer").value("답변"));
	}

	@Test void messageOfJobFinishedBeforeWorkerDiedIsAckedWithoutAnsweringAgain() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		long jobId = ChatJobTestFlow.jobId(submitted);
		// 죽은 Worker의 메시지가 남았는데 작업은 다른 메시지로 이미 끝남
		stream.ensureGroup();
		String messageId = stream.read("dead-worker", 1, null).getFirst().messageId();
		stream.publish(new ChatJobMessage(jobId, new ChatRequestContext(null, null, null)));
		assertThat(driver.processAvailableJobs()).isEqualTo(1);
		leaveIdle(messageId, 1);

		driver.recover();

		verify(llm, times(1)).generateAnswer(any());
		assertThat(pendingMessages()).isZero();
		assertThat(questions.count()).isEqualTo(1);
	}

	@Test void jobThatKeepsKillingWorkersFailsWithInternalError() throws Exception {
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		// 실행할 때마다 Worker가 죽어 메시지가 이미 최대 횟수(3번) 전달됨
		leftByKilledWorker(ChatJobTestFlow.jobId(submitted), 3);

		driver.recover();

		verify(llm, never()).generateAnswer(any());
		assertThat(pendingMessages()).isZero();
		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("FAILED"))
				.andExpect(jsonPath("$.data.errorCode").value("CHAT-015"));
	}

	@Test void runningJobWhoseMessageWasLostFailsWithInternalError() throws Exception {
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		leftByKilledWorker(ChatJobTestFlow.jobId(submitted), 1);
		// Redis가 영속화 없이 재시작해 넘겨받을 메시지까지 사라짐
		redis.delete(ChatJobStream.STREAM_KEY);

		driver.recover();

		verify(llm, never()).generateAnswer(any());
		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("FAILED"))
				.andExpect(jsonPath("$.data.errorCode").value("CHAT-015"));
	}

	@Test void statusFallsBackToQuestionLogWhenResultIsMissing() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));
		var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		driver.processAvailableJobs();
		// 결과 키가 만료되거나 Redis 데이터가 사라짐
		redis.delete(redis.keys("chat:jobs:result:*"));

		mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.data.answer").value("답변"))
				.andExpect(jsonPath("$.data.store").doesNotExist());
	}

	@Test
	void numberedLlmFailureCodeAllowsOwnedRetry() throws Exception {
		String key = "f".repeat(64);
		var first = new AnswerAttemptsHistory(user.getId(), "질문", 1, key, LocalDateTime.now(), "", "");
		first.fail(LlmErrorCode.LLM_TIMEOUT);
		first = attempts.saveAndFlush(first);
		assertThat(jdbc.queryForObject("select error_code from answer_attempts_history where attempt_id = ?",
				String.class, first.getId())).isEqualTo("LLM-004");
		assertThat(attempts.findById(first.getId()).orElseThrow().getErrorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT);
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL)));
		when(llm.generateAnswer(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));

		String body = complete(post("/chat/questions/retries").header("Idempotency-Key", key), LlmErrorCode.LLM_TIMEOUT);

		assertThat(body).contains("\"attemptCount\":2", "\"retryable\":true", key);
		var latest = attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(user.getId(), key).orElseThrow();
		assertThat(jdbc.queryForObject("select error_code from answer_attempts_history where attempt_id = ?",
				String.class, latest.getId())).isEqualTo("LLM-004");
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
	}

	@Test void committedSuccessIncludesMapBeforeRedisWriteAndAfterCacheLoss() {
		Long conversation = conversations.createGuestConversation();
		var submitted = service.createGuestChat(conversation, "주변 매장", new com.ubot.ai.dto.Location(37.5, 127.0), null);
		long id = submitted.response().jobId();
		LocalDateTime started = jobQueue.claim(id, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).orElseThrow().startedAt();
		var nearby = new com.ubot.store.dto.NearbyStoreResponseDto(123L, "테스트 매장", "서울", "강남구",
				"테스트 주소", "02-000-0000", "09:00-18:00", 37.51, 127.01, 0.5);
		var map = new com.ubot.ai.dto.StoreMapResult(new com.ubot.ai.dto.Location(37.5, 127.0), null, 3, List.of(nearby));
		var store = new com.ubot.chat.dto.response.ChatStoreDto(true, map);
		completion.complete(id, started, new com.ubot.chat.service.ChatJobOutcome("지도 답변", List.of(), store, null, List.of()), null);
		assertThat(jdbc.queryForObject("select request_context from chat_jobs where job_id = ?", String.class, id)).isNull();

		// Worker의 Redis 저장 전에도 성공 커밋만으로 지도와 위치 요청을 읽을 수 있어야 함
		assertThat(redis.opsForValue().get("chat:jobs:result:" + id)).isNull();
		var result = query.getStatus(id, null, java.util.Optional.of(conversation));
		assertThat(result.answer()).isEqualTo("지도 답변");
		assertThat(result.store()).isEqualTo(store);
		redis.opsForValue().set("chat:jobs:result:" + id, "broken-cache");
		assertThat(query.getStatus(id, null, java.util.Optional.of(conversation)))
				.usingRecursiveComparison().ignoringFields("serverTime").isEqualTo(result);
		redis.delete("chat:jobs:result:" + id);
		assertThat(query.getStatus(id, null, java.util.Optional.of(conversation)))
				.usingRecursiveComparison().ignoringFields("serverTime").isEqualTo(result);
	}

	@ParameterizedTest
	@ValueSource(strings = {"execution-failure", "queue-expiry", "orphaned-execution"})
	void terminalFailuresClearRequestContext(String failure) {
		Long conversation = conversations.createGuestConversation();
		var context = new ChatRequestContext(37.5, 127.0, com.ubot.ranking.enums.RegionSido.SEOUL);
		long id = submissions.submit(SubmissionType.QUESTION, false, () -> attemptsService.createGuestAnswerAttempt(conversation, "내 주변 매장"), context)
				.response().jobId();
		LocalDateTime started = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MICROS);
		if (failure.equals("queue-expiry")) {
			assertThat(completion.failQueued(id, ChatErrorCode.RESPONSE_TIMEOUT)).isTrue();
		} else {
			jobQueue.claim(id, started).orElseThrow();
			if (failure.equals("orphaned-execution")) {
				assertThat(completion.failStuckRunning(id, LocalDateTime.now().minusMinutes(10), ChatErrorCode.RESPONSE_TIMEOUT)).isTrue();
			} else {
				assertThat(completion.complete(id, started,
						com.ubot.chat.service.ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT), context.region()).recorded()).isTrue();
			}
		}
		assertThat(jdbc.queryForObject("select request_context from chat_jobs where job_id = ?", String.class, id)).isNull();
		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(com.ubot.chat.job.entity.ChatJobStatus.FAILED);
		assertThat(query.getStatus(id, null, java.util.Optional.of(conversation)).errorCode()).isEqualTo(ChatErrorCode.RESPONSE_TIMEOUT.getCode());
	}

	@Test void rolledBackCompletionPreservesContextForRecovery() {
		Long conversation = conversations.createGuestConversation();
		var context = new ChatRequestContext(37.5, 127.0, com.ubot.ranking.enums.RegionSido.SEOUL);
		long id = submissions.submit(SubmissionType.QUESTION, false, () -> attemptsService.createGuestAnswerAttempt(conversation, "내 주변 매장"), context)
				.response().jobId();
		LocalDateTime started = jobQueue.claim(id, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).orElseThrow().startedAt();
		new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
			completion.complete(id, started, new com.ubot.chat.service.ChatJobOutcome("답변", List.of(), null, null, List.of()), context.region());
			jobs.flush();
			assertThat(jdbc.queryForObject("select request_context from chat_jobs where job_id = ?", String.class, id)).isNull();
			status.setRollbackOnly();
		});
		assertThat(jobs.findById(id).orElseThrow().getRequestContext()).isEqualTo(context);
		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(com.ubot.chat.job.entity.ChatJobStatus.RUNNING);
		assertThat(questions.count()).isZero();
		assertThat(jobQueue.release(id, started)).isEqualTo(1);
		assertThat(jobs.findById(id).orElseThrow().getRequestContext()).isEqualTo(context);
	}

	@Test void redisLossRecoveryPreservesCoordinatesAndRegionFromDatabase() {
		Long conversation = conversations.createGuestConversation();
		var context = new ChatRequestContext(37.5, 127.0, com.ubot.ranking.enums.RegionSido.SEOUL);
		var submitted = submissions.submit(SubmissionType.QUESTION, false, () -> attemptsService.createGuestAnswerAttempt(conversation, "내 주변 매장"), context);
		long id = submitted.response().jobId();
		assertThat(jobs.findById(id).orElseThrow().getRequestContext()).isEqualTo(context);
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:published:*"));
		jdbc.update("update chat_jobs set created_at = CURRENT_TIMESTAMP - interval '1 minute' where job_id = ?", id);
		driver.recover();
		stream.ensureGroup();
		assertThat(stream.read("recovered", 10, null)).singleElement()
				.satisfies(received -> assertThat(received.message()).isEqualTo(new ChatJobMessage(id, context)));
	}

	// 스트림만 사라지고 발행 표시는 남은 채 새 질문이 스트림을 다시 만듦
	@Test void queuedJobIsRecoveredWhenStreamIsLostButMarksRemain() {
		Long conversation = conversations.createGuestConversation();
		var context = new ChatRequestContext(null, null, com.ubot.ranking.enums.RegionSido.SEOUL);
		long lost = submissions.submit(SubmissionType.QUESTION, false, () -> attemptsService.createGuestAnswerAttempt(conversation, "잃어버린 질문"), context)
				.response().jobId();
		redis.delete(ChatJobStream.STREAM_KEY);
		long fresh = submissions.submit(SubmissionType.QUESTION, false, () -> attemptsService.createGuestAnswerAttempt(conversation, "새 질문"), context)
				.response().jobId();
		jdbc.update("update chat_jobs set created_at = CURRENT_TIMESTAMP - interval '1 minute' where job_id = ?", lost);

		driver.recover();
		stream.ensureGroup();

		assertThat(stream.read("recovered", 10, null)).extracting(received -> received.message().jobId())
				.containsExactlyInAnyOrder(lost, fresh);
	}

	@Test void submissionWaitsForOuterCommitAndRollbackPublishesNothing() {
		Long conversation = conversations.createGuestConversation();
		stream.ensureGroup();
		var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
		tx.executeWithoutResult(status -> {
			service.createGuestChat(conversation, "롤백 질문", null, null);
			assertThat(stream.read("probe", 10, null)).isEmpty();
			status.setRollbackOnly();
		});
		assertThat(jobs.count()).isZero();
		assertThat(attempts.count()).isZero();
		assertThat(stream.read("probe", 10, null)).isEmpty();
		Long committed = tx.execute(status -> {
			var submission = service.createGuestChat(conversation, "커밋 질문", null, null);
			assertThat(stream.read("probe", 10, null)).isEmpty();
			return submission.response().jobId();
		});
		assertThat(stream.read("probe", 10, null)).singleElement()
				.satisfies(message -> assertThat(message.message().jobId()).isEqualTo(committed));
	}

	@Test void sameGuestQuestionWithOverlappingExecutionAlwaysReturnsItsOwnResult() {
		Long conversation = conversations.createGuestConversation();
		var first = service.createGuestChat(conversation, "동일 질문", null, null);
		var second = service.createGuestChat(conversation, "동일 질문", null, null);
		long firstId = first.response().jobId(), secondId = second.response().jobId();
		LocalDateTime start = LocalDateTime.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
		jobQueue.claim(firstId, start);
		jobQueue.claim(secondId, start);
		completion.complete(firstId, start, new com.ubot.chat.service.ChatJobOutcome("첫 좌표 답변", List.of(), null, null, List.of()), null);
		completion.complete(secondId, start, new com.ubot.chat.service.ChatJobOutcome("다른 좌표 답변", List.of(), null, null, List.of()), null);
		// 첫 작업 저장 중 두 번째 로그가 생긴 경쟁 상황의 시간 범위 재현
		jdbc.update("update chat_jobs set finished_at = ? where job_id = ?", LocalDateTime.now().plusSeconds(1), firstId);
		assertThat(query.getStatus(firstId, null, java.util.Optional.of(conversation)).answer()).isEqualTo("첫 좌표 답변");
		assertThat(query.getStatus(secondId, null, java.util.Optional.of(conversation)).answer()).isEqualTo("다른 좌표 답변");
	}

	@Test void guestCanContinuePollingAfterLoginAndOtherUsersStillCannotReadIt() throws Exception {
		var session = new MockHttpSession();
		var submitted = flow.submit(post("/chat/questions").session(session)
				.contentType("application/json").content("{\"question\":\"질문\"}"));
		long id = ChatJobTestFlow.jobId(submitted);
		mvc.perform(get("/chat/jobs/{jobId}", id).session(session).header("Authorization", authorization))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
		assertThat(attempts.findAll().getFirst().getUserId()).isEqualTo(user.getId());
		mvc.perform(get("/chat/jobs/{jobId}", id)).andExpect(status().isNotFound());
		User other = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test").name("다른 회원").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		mvc.perform(get("/chat/jobs/{jobId}", id).header("Authorization", "Bearer " + jwt.createAccessToken(other)))
				.andExpect(status().isNotFound());
	}

	@Test void oldFailedJobStopsOfferingRetryWhenNewAttemptExists() {
		Long conversation = conversations.getOrCreateMemberConversation(user.getId(), null).getId();
		var first = service.createChat(user.getId(), conversation, "질문", new com.ubot.ai.dto.Location(37.5, 127.0), null);
		long id = first.response().jobId();
		LocalDateTime started = jobQueue.claim(id, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).orElseThrow().startedAt();
		completion.complete(id, started, com.ubot.chat.service.ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT), null);
		assertThat(jobs.findById(id).orElseThrow().getRequestContext()).isNull();
		assertThat(query.getStatus(id, user.getId(), java.util.Optional.empty()).retryable()).isTrue();
		var retried = service.retryChat(user.getId(), first.response().idempotencyKey(), null);
		assertThat(retried.response().jobId()).isNotEqualTo(id);
		assertThat(jobs.findById(retried.response().jobId()).orElseThrow().getRequestContext()).isEqualTo(ChatRequestContext.EMPTY);
		assertThat(query.getStatus(id, user.getId(), java.util.Optional.empty()).retryable()).isFalse();
	}

	@Test void staleOwnerCannotOverwriteOrFailTakenOverJob() {
		Long conversation = conversations.createGuestConversation();
		var context = new ChatRequestContext(37.5, 127.0, com.ubot.ranking.enums.RegionSido.SEOUL);
		var submitted = submissions.submit(SubmissionType.QUESTION, false, () -> attemptsService.createGuestAnswerAttempt(conversation, "질문"), context);
		long id = submitted.response().jobId();
		LocalDateTime old = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MICROS);
		jobQueue.claim(id, old);
		LocalDateTime current = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		jobQueue.takeOver(id, current, current.minusMinutes(5)).orElseThrow();
		assertThat(completion.complete(id, old, new com.ubot.chat.service.ChatJobOutcome("늦은 답변", List.of(), null, null, List.of()), null).recorded()).isFalse();
		assertThat(completion.complete(id, old, com.ubot.chat.service.ChatJobOutcome.failed(ChatErrorCode.RESPONSE_TIMEOUT), null).recorded()).isFalse();
		assertThat(questions.count()).isZero();
		assertThat(jobs.findById(id).orElseThrow().getRequestContext()).isEqualTo(context);
		assertThat(completion.complete(id, current, new com.ubot.chat.service.ChatJobOutcome("현재 답변", List.of(), null, null, List.of()), null).recorded()).isTrue();
		assertThat(jobs.findById(id).orElseThrow().getRequestContext()).isNull();
		assertThat(query.getStatus(id, null, java.util.Optional.of(conversation)).answer()).isEqualTo("현재 답변");
	}

	private String complete(MockHttpServletRequestBuilder request) throws Exception {
		return complete(request, null);
	}

	private String complete(MockHttpServletRequestBuilder request, ErrorCode expectedError) throws Exception {
		return flow.complete(request.header("Authorization", authorization), expectedError);
	}

	private String completeGuest(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
		return completeGuest(request, session, null);
	}

	private String completeGuest(
			MockHttpServletRequestBuilder request, MockHttpSession session, ErrorCode expectedError
	) throws Exception {
		return flow.complete(request.session(session), expectedError);
	}

	// Worker가 실행 중 강제로 죽은 상황. 메시지는 ACK 없이 1시간 방치·deliveries번 전달, 작업은 1시간 전 시작한 RUNNING
	private void leftByKilledWorker(long jobId, long deliveries) {
		stream.ensureGroup();
		String messageId = stream.read("dead-worker", 1, null).getFirst().messageId();
		assertThat(jobQueue.claim(jobId, LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MICROS))).isPresent();
		leaveIdle(messageId, deliveries);
	}

	private void leaveIdle(String messageId, long deliveries) {
		redis.opsForStream().claim(ChatJobStream.STREAM_KEY, ChatJobStream.GROUP, "dead-worker",
				XClaimOptions.minIdle(Duration.ZERO).ids(messageId).idle(Duration.ofHours(1)).retryCount(deliveries));
	}

	private long pendingMessages() {
		return redis.opsForStream().pending(ChatJobStream.STREAM_KEY, ChatJobStream.GROUP).getTotalPendingMessages();
	}

	private String keyFrom(String body) {
		var matcher = Pattern.compile("\"idempotencyKey\":\"([0-9a-f]{64})\"").matcher(body);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
