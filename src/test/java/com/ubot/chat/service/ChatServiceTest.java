package com.ubot.chat.service;

import com.ubot.ai.service.AiService;
import com.ubot.chat.controller.ChatController;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.common.ErrorCode;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import com.ubot.user.entity.User;
import java.util.stream.IntStream;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatServiceTest {
	FaqVectorService vector = mock(FaqVectorService.class);
	AiService ai = mock(AiService.class);
	AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	QuestionLogRepository questions = mock(QuestionLogRepository.class);
	FaqLogRepository faqLogs = mock(FaqLogRepository.class);
	FaqRepository faqs = mock(FaqRepository.class);
	PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
	List<AnswerAttemptsHistory> saved = new ArrayList<>();
	Deque<Runnable> jobs = new ArrayDeque<>();
	ChatService service;
	ChatAttemptsService attemptsService;
	MockMvc mvc;

	@BeforeEach void setup() {
		when(tx.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
		when(attempts.saveAndFlush(any())).thenAnswer(call -> {
			AnswerAttemptsHistory attempt = call.getArgument(0);
			ReflectionTestUtils.setField(attempt, "id", (long) saved.size() + 1);
			saved.add(attempt);
			return attempt;
		});
		when(attempts.findAttemptForLock(anyLong())).thenAnswer(call ->
				saved.stream().filter(a -> a.getId().equals(call.getArgument(0))).findFirst());
		when(attempts.findInitialAttemptsForLock(anyLong(), anyString(), eq(1))).thenAnswer(call ->
				saved.stream().filter(a -> a.getUserId().equals(call.getArgument(0))
						&& a.getIdempotencyKey().equals(call.getArgument(1))
						&& a.getAttemptCount() == 1).findFirst());
		when(attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(anyLong(), anyString()))
				.thenAnswer(call -> saved.stream().filter(a -> a.getUserId().equals(call.getArgument(0))
						&& a.getIdempotencyKey().equals(call.getArgument(1)))
						.max(Comparator.comparingInt(AnswerAttemptsHistory::getAttemptCount)));
		when(questions.saveAndFlush(any())).thenAnswer(call -> {
			QuestionLog result = call.getArgument(0);
			ReflectionTestUtils.setField(result, "id", 10L);
			return result;
		});
		when(faqs.getReferenceById(anyLong())).thenAnswer(call -> Faq.builder().id(call.getArgument(0)).build());
		attemptsService = new ChatAttemptsService(attempts, questions, faqLogs, faqs, tx);
		ReflectionTestUtils.setField(attemptsService, "maxAttempts", 3);
		service = new ChatService(vector, ai, attemptsService, jobs::add);
		ReflectionTestUtils.setField(service, "topK", 3);
		ReflectionTestUtils.setField(service, "confidenceThreshold", 0.75);
		var controller = new ChatController(service);
		ReflectionTestUtils.setField(controller, "responseTimeoutMillis", 180_000L);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CustomUserDetails.class; }
					public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r, org.springframework.web.bind.support.WebDataBinderFactory x) {
						return new CustomUserDetails(User.builder().id(1L).build());
					}
				}).build();
	}
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void invalidQuestionsDoNotAccessDbOrLlm(String question) {
		assertThatThrownBy(() -> service.createChat(1L, question)).isInstanceOf(ChatException.class);
		verifyNoInteractions(attempts, vector, ai);
	}

	@Test void keyUsesUserQuestionAndTheRecordedCreationTime() {
		service.createChat(1L, "질문");
		var attempt = saved.getFirst();
		assertThat(attempt.getIdempotencyKey()).isEqualTo(
				ChatAttemptsService.createIdempotencyKey(1L, "질문", attempt.getCreatedAt())).hasSize(64);
		assertThat(ChatAttemptsService.createIdempotencyKey(2L, "질문", attempt.getCreatedAt())).isNotEqualTo(attempt.getIdempotencyKey());
		assertThat(ChatAttemptsService.createIdempotencyKey(1L, "다른 질문", attempt.getCreatedAt())).isNotEqualTo(attempt.getIdempotencyKey());
		assertThat(ChatAttemptsService.createIdempotencyKey(1L, "질문", attempt.getCreatedAt().plusNanos(1000))).isNotEqualTo(attempt.getIdempotencyKey());
	}

	@Test void successReturnsFinalJsonAndSavesAllFaqReferences() throws Exception {
		var sources = List.of(new FaqSearchResponseDto(1L, "유심 재발급", "매장 방문", 0.9),
				new FaqSearchResponseDto(2L, "준비물", "준비물 원문", 0.8));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer("질문", sources)).thenReturn(new LlmResponseDto("생성된 답변"));
		String body = completeRequest();
		assertThat(body).contains("\"status\":\"SUCCESS\"", "\"success\":true", "생성된 답변")
				.doesNotContain("event:", "data:");
		assertThat(saved.getFirst().getStatus()).isEqualTo("SUCCESS");
		verify(ai).generateAnswer("질문", sources);
		verify(questions).saveAndFlush(argThat(q -> q.getUserId() == 1L
				&& q.getUserQuestion().equals("질문") && q.getAnswer().equals("생성된 답변")));
		verify(faqLogs).saveAll(argThat(items -> {
			var list = new ArrayList<com.ubot.faq.entity.FaqLog>();
			items.forEach(list::add);
			return list.size() == 2 && list.get(0).getQuestionLogId() == 10L
					&& list.get(0).getRank() == 1 && list.get(1).getRank() == 2
					&& list.get(0).getCreatedAt() != null
					&& list.get(0).getCreatedAt().equals(list.get(1).getCreatedAt());
		}));
	}

	@Test void noResultsSkipLlmAndPersistFailure() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());
		assertThat(completeRequest(ChatErrorCode.NO_FAQ)).contains("검색 결과가 없습니다.", "\"status\":\"FAIL\"");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.NO_FAQ);
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test void insufficientSimilaritySkipsLlm() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.74)));
		assertThat(completeRequest(ChatErrorCode.INSUFFICIENT_FAQ)).contains("정확한 답변을 찾지 못했습니다.", "\"status\":\"FAIL\"");
		verifyNoInteractions(ai);
	}

	@Test void thresholdEqualityStillCallsLlm() throws Exception {
		var sources = List.of(new FaqSearchResponseDto(1L, "q", "a", 0.75));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer("질문", sources)).thenReturn(new LlmResponseDto("답변"));
		assertThat(completeRequest()).contains("\"status\":\"SUCCESS\"");
	}

	@Test void configuredSearchSizeAndThresholdAreUsed() throws Exception {
		ReflectionTestUtils.setField(service, "topK", 5);
		ReflectionTestUtils.setField(service, "confidenceThreshold", 0.8);
		when(vector.getSimilarList("질문", 5)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.79)));

		assertThat(completeRequest(ChatErrorCode.INSUFFICIENT_FAQ)).contains("\"status\":\"FAIL\"", "\"retryable\":false");
		verify(vector).getSimilarList("질문", 5);
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
		verifyNoInteractions(ai);
	}

	@ParameterizedTest @EnumSource(LlmErrorCode.class)
	void llmExceptionsAreRecordedAndExposeOnlySafeMessages(LlmErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		when(ai.generateAnswer(anyString(), anyList())).thenThrow(new LlmException(code, new RuntimeException("secret")));
		assertThat(completeRequest(code)).contains("\"status\":\"FAIL\"", "\"success\":false", "\"retryable\":true")
				.contains(code.getMessage(), saved.getFirst().getIdempotencyKey()).doesNotContain("secret");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(code);
		assertThat(saved.getFirst().getErrorCode().getCode()).isEqualTo(code.getCode());
		assertThat(saved.getFirst().getErrorMessage()).isEqualTo(code.getMessage());
		verifyNoInteractions(questions, faqLogs);
	}

	@ParameterizedTest @EnumSource(EmbeddingErrorCode.class)
	void embeddingExceptionsAreRecorded(EmbeddingErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenThrow(new EmbeddingException(code));
		assertThat(completeRequest(code)).contains("\"status\":\"FAIL\"", "\"retryable\":true", code.getMessage());
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(code);
		assertThat(saved.getFirst().getErrorMessage()).isEqualTo(code.getMessage());
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test void missingPromptBecomesRecordedFailure() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		when(ai.generateAnswer(anyString(), anyList())).thenThrow(new PromptException("준비 안 됨"));
		assertThat(completeRequest(ChatErrorCode.PROMPT_NOT_READY)).contains("\"status\":\"FAIL\"", "\"retryable\":false",
				ChatErrorCode.PROMPT_NOT_READY.getMessage());
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.PROMPT_NOT_READY);
		assertThat(attemptsService.validateRetryAttempt(saved.getFirst())).contains(ChatErrorCode.RETRY_NOT_ALLOWED);
		assertChatError(() -> service.retryChat(1L, saved.getFirst().getIdempotencyKey()), ChatErrorCode.RETRY_NOT_ALLOWED);
		assertThat(saved).hasSize(1);
		assertThat(jobs).isEmpty();
	}

	@Test void pendingAndSucceededAttemptsCannotStartAnotherGeneration() {
		service.createChat(1L, "질문");
		String key = saved.getFirst().getIdempotencyKey();
		assertThat(attemptsService.validateRetryAttempt(saved.getFirst())).contains(ChatErrorCode.PROCESSING);
		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.PROCESSING);
		saved.getFirst().succeed();
		assertThat(attemptsService.validateRetryAttempt(saved.getFirst())).contains(ChatErrorCode.ALREADY_SUCCEEDED);
		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.ALREADY_SUCCEEDED);
		assertThat(saved).hasSize(1);
		assertThat(jobs).hasSize(1);
	}

	@ParameterizedTest @ValueSource(ints = {1, 2, 3})
	void configuredAttemptLimitKeepsKeyAndBlocksNextAttempt(int maxAttempts) {
		ReflectionTestUtils.setField(attemptsService, "maxAttempts", maxAttempts);
		when(vector.getSimilarList("질문", 3)).thenThrow(new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT));
		service.createChat(1L, "질문");
		jobs.remove().run();
		String key = saved.getFirst().getIdempotencyKey();
		for (int count = 2; count <= maxAttempts; count++) {
			service.retryChat(1L, key);
			jobs.remove().run();
		}
		assertThat(saved).extracting(AnswerAttemptsHistory::getAttemptCount)
				.containsExactlyElementsOf(IntStream.rangeClosed(1, maxAttempts).boxed().toList());
		assertThat(saved).allMatch(a -> a.getIdempotencyKey().equals(key) && a.getStatus().equals("FAIL"));
		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.LIMIT_REACHED);
		assertThat(saved).hasSize(maxAttempts);
	}

	@Test void retryRejectsUnknownKey() {
		assertChatError(() -> service.retryChat(1L, "a".repeat(64)), ChatErrorCode.ATTEMPT_NOT_FOUND);
		assertThat(saved).isEmpty();
		assertThat(jobs).isEmpty();
		verifyNoInteractions(vector, ai);
	}

	@Test
	void failureStorageErrorReturnsSafeErrorWithoutEnablingRetry() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());
		doThrow(new DataAccessResourceFailureException("database secret"))
				.when(attempts).findAttemptForLock(anyLong());

		String body = completeRequest(ChatErrorCode.STORAGE_UNAVAILABLE);

		assertThat(body).contains("\"success\":false", "\"retryable\":false", saved.getFirst().getIdempotencyKey())
				.doesNotContain("database secret");
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test
	void rejectedWorkerCompletesWithRecordedFailure() {
		service = new ChatService(vector, ai, attemptsService, task -> {
			throw new java.util.concurrent.RejectedExecutionException("executor secret");
		});
		var task = service.createChat(1L, "질문");

		assertThatThrownBy(() -> task.result().join()).hasCauseInstanceOf(ChatException.class)
				.satisfies(exception -> {
					var failure = (ChatException) exception.getCause();
					assertThat(failure.getErrorCode()).isEqualTo(ChatErrorCode.TASK_START_FAILED);
					assertThat(failure.getResponse().idempotencyKey()).isEqualTo(saved.getFirst().getIdempotencyKey());
					assertThat(failure.getResponse().retryable()).isFalse();
				});
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.TASK_START_FAILED);
		verifyNoInteractions(vector, ai, questions, faqLogs);
	}

	@ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"not-a-key"})
	void invalidRetryKeyDoesNotTouchDb(String key) {
		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		verifyNoInteractions(attempts);
	}

	@Test void unexpectedExceptionUsesRegisteredErrorCodeWithoutExposingCause() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		when(ai.generateAnswer(anyString(), anyList())).thenThrow(new IllegalStateException("secret"));

		assertThat(completeRequest(ChatErrorCode.INTERNAL_ERROR)).contains("\"status\":\"FAIL\"", "\"retryable\":false",
				ChatErrorCode.INTERNAL_ERROR.getMessage()).doesNotContain("secret");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.INTERNAL_ERROR);
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void attemptRepositoryFailureReachesCommonExceptionHandler(boolean retry) throws Exception {
		var failure = new DataAccessResourceFailureException("database secret");
		var request = retry
				? post("/chat/questions/retries").header("Idempotency-Key", "a".repeat(64))
				: post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}");
		if (retry) {
			doThrow(failure).when(attempts).findInitialAttemptsForLock(anyLong(), anyString(), eq(1));
		} else {
			doThrow(failure).when(attempts).saveAndFlush(any());
		}

		var result = mvc.perform(request).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("G-005"))
				.andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
				.andReturn();
		assertThat(result.getResolvedException()).isSameAs(failure);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("database secret");
		assertThat(jobs).isEmpty();
		verifyNoInteractions(vector, ai);
	}

	@ParameterizedTest
	@ValueSource(strings = {"SUCCESS", "FAIL"})
	void completedAttemptRejectsLateSuccessLogsAndFailure(String status) {
		service.createChat(1L, "질문");
		var stored = saved.getFirst();
		if ("SUCCESS".equals(status)) {
			stored.succeed();
		} else {
			stored.fail(ChatErrorCode.RESPONSE_TIMEOUT);
		}
		var previousError = stored.getErrorCode();
		var previousMessage = stored.getErrorMessage();
		var staleAttempt = AnswerAttemptsHistory.builder().id(stored.getId()).userId(1L)
				.question("질문").status("PENDING").build();

		attemptsService.saveAnswerSuccess(staleAttempt, "늦게 도착한 답변",
				List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		attemptsService.saveAnswerFailure(staleAttempt, ChatErrorCode.INTERNAL_ERROR);

		assertThat(stored.getStatus()).isEqualTo(status);
		assertThat(stored.getErrorCode()).isEqualTo(previousError);
		assertThat(stored.getErrorMessage()).isEqualTo(previousMessage);
		verifyNoInteractions(questions, faqLogs, faqs);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3, 5})
	void timeoutRetriesRespectConfiguredAttemptLimit(int maxAttempts) {
		ReflectionTestUtils.setField(attemptsService, "maxAttempts", maxAttempts);
		service.createChat(1L, "질문");
		String key = saved.getFirst().getIdempotencyKey();

		for (int count = 1; count <= maxAttempts; count++) {
			var attempt = saved.getLast();
			attempt.fail(ChatErrorCode.RESPONSE_TIMEOUT);
			assertThat(attempt.getAttemptCount()).isEqualTo(count);
			assertThat(attemptsService.validateRetryAttempt(attempt))
					.isEqualTo(count < maxAttempts ? Optional.empty() : Optional.of(ChatErrorCode.LIMIT_REACHED));
			if (count < maxAttempts) {
				service.retryChat(1L, key);
			}
		}

		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.LIMIT_REACHED);
		assertThat(saved).hasSize(maxAttempts).allSatisfy(attempt ->
				assertThat(attempt.getIdempotencyKey()).isEqualTo(key));
	}

	@Test
	void timeoutRetryStillRejectsAnotherUsersKey() {
		service.createChat(1L, "질문");
		var attempt = saved.getFirst();
		attempt.fail(ChatErrorCode.RESPONSE_TIMEOUT);

		assertChatError(() -> service.retryChat(2L, attempt.getIdempotencyKey()),
				ChatErrorCode.ATTEMPT_NOT_FOUND);
		assertThat(saved).hasSize(1);
		verifyNoInteractions(vector, ai);
	}

	private String completeRequest() throws Exception {
		return completeRequest(null);
	}

	private String completeRequest(ErrorCode expectedError) throws Exception {
		var result = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}")).andExpect(request().asyncStarted()).andReturn();
		verifyNoInteractions(vector, ai); // 답변 생성은 요청 스레드에서 실행하지 않습니다.
		assertThat(result.getResponse().getContentAsString()).isEmpty();
		jobs.remove().run();
		return mvc.perform(asyncDispatch(result))
				.andExpect(status().is(expectedError == null ? 200 : expectedError.getStatus().value()))
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(expectedError == null))
				.andExpect(jsonPath("$.code").value(expectedError == null ? "SUCCESS" : expectedError.getCode()))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.data.success").doesNotExist())
				.andExpect(jsonPath("$.data.answer").isString()).andReturn()
				.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private void assertChatError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ChatErrorCode code) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChatException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
