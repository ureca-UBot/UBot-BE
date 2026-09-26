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
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
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
		when(attempts.findById(anyLong())).thenAnswer(call ->
				saved.stream().filter(a -> a.getId().equals(call.getArgument(0))).findFirst());
		when(attempts.findInitialAttemptsForLock(anyString(), eq(1))).thenAnswer(call ->
				saved.stream().filter(a -> a.getIdempotencyKey().equals(call.getArgument(0))
						&& a.getAttemptCount() == 1).findFirst());
		when(attempts.findFirstByIdempotencyKeyOrderByAttemptCountDesc(anyString()))
				.thenAnswer(call -> saved.stream().filter(a -> a.getIdempotencyKey().equals(call.getArgument(0)))
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
		mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
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
		assertThat(completeRequest()).contains("검색 결과가 없습니다.", "\"status\":\"FAIL\"");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo("CHAT_NO_FAQ");
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test void insufficientSimilaritySkipsLlm() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.74)));
		assertThat(completeRequest()).contains("정확한 답변을 찾지 못했습니다.", "\"status\":\"FAIL\"");
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

		assertThat(completeRequest()).contains("\"status\":\"FAIL\"", "\"retryable\":false");
		verify(vector).getSimilarList("질문", 5);
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ.getCode());
		verifyNoInteractions(ai);
	}

	@ParameterizedTest @EnumSource(LlmErrorCode.class)
	void llmExceptionsAreRecordedAndExposeOnlySafeMessages(LlmErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		when(ai.generateAnswer(anyString(), anyList())).thenThrow(new LlmException(code, new RuntimeException("secret")));
		assertThat(completeRequest()).contains("\"status\":\"FAIL\"", "\"success\":true", "\"retryable\":true")
				.contains(code.getMessage(), saved.getFirst().getIdempotencyKey()).doesNotContain("secret");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(code.name());
		assertThat(saved.getFirst().getErrorMessage()).isEqualTo(code.getMessage());
		verifyNoInteractions(questions, faqLogs);
	}

	@ParameterizedTest @EnumSource(EmbeddingErrorCode.class)
	void embeddingExceptionsAreRecorded(EmbeddingErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenThrow(new EmbeddingException(code));
		assertThat(completeRequest()).contains("\"status\":\"FAIL\"", code.getMessage());
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(code.getCode());
		assertThat(saved.getFirst().getErrorMessage()).isEqualTo(code.getMessage());
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test void missingPromptBecomesRecordedFailure() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		when(ai.generateAnswer(anyString(), anyList())).thenThrow(new PromptException("준비 안 됨"));
		assertThat(completeRequest()).contains("\"status\":\"FAIL\"", "\"retryable\":false",
				ChatErrorCode.PROMPT_NOT_READY.getMessage());
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.PROMPT_NOT_READY.getCode());
	}

	@Test void pendingAndSucceededAttemptsCannotStartAnotherGeneration() {
		service.createChat(1L, "질문");
		String key = saved.getFirst().getIdempotencyKey();
		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.PROCESSING);
		saved.getFirst().succeed();
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

	@ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"not-a-key"})
	void invalidRetryKeyDoesNotTouchDb(String key) {
		assertChatError(() -> service.retryChat(1L, key), ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		verifyNoInteractions(attempts);
	}

	@Test void unexpectedExceptionUsesRegisteredErrorCodeWithoutExposingCause() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9)));
		when(ai.generateAnswer(anyString(), anyList())).thenThrow(new IllegalStateException("secret"));

		assertThat(completeRequest()).contains("\"status\":\"FAIL\"", "\"retryable\":false",
				ChatErrorCode.INTERNAL_ERROR.getMessage()).doesNotContain("secret");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.INTERNAL_ERROR.getCode());
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void attemptRepositoryFailureReachesCommonExceptionHandler(boolean retry) throws Exception {
		var failure = new DataAccessResourceFailureException("database secret");
		var request = retry
				? post("/chat/questions/retries").header("Idempotency-Key", "a".repeat(64))
				: post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}");
		if (retry) {
			doThrow(failure).when(attempts).findInitialAttemptsForLock(anyString(), eq(1));
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

	private String completeRequest() throws Exception {
		var result = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}")).andExpect(request().asyncStarted()).andReturn();
		verifyNoInteractions(vector, ai); // 답변 생성은 요청 스레드에서 실행하지 않습니다.
		assertThat(result.getResponse().getContentAsString()).isEmpty();
		jobs.remove().run();
		return mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
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
