package com.ubot.chat.service;

import com.ubot.conversation.service.ConversationService;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.controller.ChatController;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.common.ErrorCode;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.guest.entity.GuestChatSettings;
import com.ubot.guest.repository.GuestChatSettingsRepository;
import com.ubot.guest.session.GuestConversationService;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.user.entity.User;
import java.nio.charset.StandardCharsets;
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
	QuestionLogService questions = mock(QuestionLogService.class);
	FaqLogRepository faqLogs = mock(FaqLogRepository.class);
	FaqRepository faqs = mock(FaqRepository.class);
	ConversationRepository conversations = mock(ConversationRepository.class);
	GuestChatSettingsRepository guestSettings = mock(GuestChatSettingsRepository.class);
	PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
	LlmService llmService = mock(LlmService.class);
	EmbeddingService embeddingService = mock(EmbeddingService.class);
	IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	List<AnswerAttemptsHistory> saved = new ArrayList<>();
	ChatJobTestHarness harness;
	ChatService service;
	ChatAnswerProcessor processor;
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
		when(attempts.findAttemptForLock(anyLong())).thenAnswer(call -> findSaved(call.getArgument(0)));
		when(attempts.findById(anyLong())).thenAnswer(call -> findSaved(call.getArgument(0)));
		when(attempts.findInitialAttemptsForLock(anyLong(), anyString(), eq(1))).thenAnswer(call ->
				saved.stream().filter(a -> a.getUserId().equals(call.getArgument(0))
						&& a.getIdempotencyKey().equals(call.getArgument(1))
						&& a.getAttemptCount() == 1).findFirst());
		when(attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(anyLong(), anyString()))
				.thenAnswer(call -> saved.stream().filter(a -> a.getUserId().equals(call.getArgument(0))
						&& a.getIdempotencyKey().equals(call.getArgument(1)))
						.max(Comparator.comparingInt(AnswerAttemptsHistory::getAttemptCount)));
		when(questions.saveTurn(any(), any(), any(), anyString(), anyList(), anyString(), nullable(RegionSido.class)))
				.thenAnswer(call -> {
			List<SubQuestionFaqs> subQuestions = call.getArgument(4);
			List<QuestionLog> results = new ArrayList<>();
			for (int index = 0; index < subQuestions.size(); index++) {
				QuestionLog result = QuestionLog.builder()
						.userId(call.getArgument(0))
						.conversationId(call.getArgument(1))
						.attemptId(call.getArgument(2))
						.userQuestion(call.getArgument(3))
						.subQuestion(subQuestions.get(index).searchQuery())
						.userText(subQuestions.get(index).userText())
						.subQuestionIndex(index + 1)
						.answer(call.getArgument(5))
						.build();
				ReflectionTestUtils.setField(result, "id", 10L + index);
				results.add(harness.saveLog(result));
			}
			return results;
		});
		when(faqs.getReferenceById(anyLong())).thenAnswer(call -> Faq.builder().id(call.getArgument(0)).build());
		when(llmService.getModelName()).thenReturn("recorded-chat-model");
		when(embeddingService.getModelName()).thenReturn("recorded-embedding-model");
		attemptsService = new ChatAttemptsService(
				attempts, questions, faqLogs, faqs, conversations, guestSettings, llmService, embeddingService, tx);
		ReflectionTestUtils.setField(attemptsService, "maxAttempts", 3);
		processor = new ChatAnswerProcessor(
				FaqRetrievalService.denseOnly(vector), ai, mock(UnansweredQuestionService.class), ChatTestFixtures.collector(), ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache());
		ReflectionTestUtils.setField(processor, "topK", 3);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
		harness = new ChatJobTestHarness(attempts, attemptsService, processor, tx);
		service = new ChatService(attemptsService, mock(ForbiddenWordFilterService.class),
				harness.submissionService, ipRegionResolver);
		var conversationService = mock(ConversationService.class);
		when(conversationService.getOrCreateMemberConversation(eq(1L), any())).thenReturn(Conversation.createMember(1L));
		var controller = new ChatController(service, harness.queryService, mock(ClientIpResolver.class),
				mock(GuestConversationService.class), conversationService);
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
		assertThatThrownBy(() -> service.createChat(1L, null, question, null, null))
				.isInstanceOf(ChatException.class);
		verifyNoInteractions(attempts, vector, ai);
		assertThat(harness.jobs).isEmpty();
	}

	@Test void keyUsesUserQuestionAndTheRecordedCreationTime() {
		service.createChat(1L, null, "질문", null, null);
		var attempt = saved.getFirst();
		assertThat(attempt.getIdempotencyKey()).isEqualTo(
				ChatAttemptsService.createIdempotencyKey(1L, "질문", attempt.getCreatedAt())).hasSize(64);
		assertThat(ChatAttemptsService.createIdempotencyKey(2L, "질문", attempt.getCreatedAt())).isNotEqualTo(attempt.getIdempotencyKey());
		assertThat(ChatAttemptsService.createIdempotencyKey(1L, "다른 질문", attempt.getCreatedAt())).isNotEqualTo(attempt.getIdempotencyKey());
		assertThat(ChatAttemptsService.createIdempotencyKey(1L, "질문", attempt.getCreatedAt().plusNanos(1000))).isNotEqualTo(attempt.getIdempotencyKey());
	}

	@Test void questionIsQueuedTogetherWithItsAttempt() {
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.of(RegionSido.SEOUL));
		var submission = service.createChat(1L, null, "질문", null, "127.0.0.1");

		assertThat(harness.jobs).singleElement().satisfies(job -> {
			assertThat(job.getStatus()).isEqualTo(ChatJobStatus.QUEUED);
			assertThat(job.getAttemptId()).isEqualTo(saved.getFirst().getId());
		});
		assertThat(harness.published).as("커밋 뒤 IP 대신 지역을 담아 발행").containsExactly(new ChatJobMessage(
				harness.jobs.getFirst().getId(), new ChatRequestContext(null, null, RegionSido.SEOUL)));
		assertThat(submission.response().jobId()).isEqualTo(harness.jobs.getFirst().getId());
		assertThat(submission.response().idempotencyKey()).isEqualTo(saved.getFirst().getIdempotencyKey());
		assertThat(saved.getFirst().getStatus()).isEqualTo("PENDING");
		verifyNoInteractions(vector, ai);
	}

	@Test void publishFailureIsRepublishedByCleanupAndAnswered() {
		var sources = List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenReturn(new AiAnswer("답변", null, false));
		when(ipRegionResolver.resolve("127.0.0.1")).thenReturn(Optional.of(RegionSido.SEOUL));
		harness.failPublishes(1);

		var submission = service.createChat(1L, null, "질문", null, "127.0.0.1");

		assertThat(harness.jobs.getFirst().getStatus()).as("발행에 실패해도 접수는 됨").isEqualTo(ChatJobStatus.QUEUED);
		assertThat(harness.runQueuedJobs()).as("스트림에는 메시지가 없음").isZero();

		harness.worker.recover();

		assertThat(harness.published).as("접수 당시 지역을 보존해 다시 발행").containsExactly(
				new ChatJobMessage(harness.jobs.getFirst().getId(), new ChatRequestContext(null, null, RegionSido.SEOUL)));
		assertThat(harness.runQueuedJobs()).isEqualTo(1);
		assertThat(harness.memberStatus(submission, 1L).status()).isEqualTo(ChatJobStatus.SUCCEEDED);
		verify(questions).saveTurn(1L, null, saved.getFirst().getId(), "질문",
				List.of(new SubQuestionFaqs("질문", sources, "질문")), "답변", RegionSido.SEOUL);
	}

	@Test void publishedQueuedJobIsNotRepublished() {
		service.createChat(1L, null, "질문", null, null);

		harness.worker.recover();

		assertThat(harness.published).as("줄 서서 기다리는 작업은 다시 발행하지 않음").hasSize(1);
	}

	@Test void successReturnsFinalJsonAndSavesAllFaqReferences() throws Exception {
		var sources = List.of(new FaqSearchResponseDto(1L, "유심 재발급", "매장 방문", 0.9, Intent.GENERAL),
				new FaqSearchResponseDto(2L, "준비물", "준비물 원문", 0.8, Intent.GENERAL));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenReturn(new AiAnswer("생성된 답변", null, false));
		String body = completeRequest();
		assertThat(body).contains("\"status\":\"SUCCEEDED\"", "\"success\":true", "생성된 답변")
				.doesNotContain("event:", "data:");
		assertThat(saved.getFirst().getStatus()).isEqualTo("SUCCESS");
		assertThat(harness.acked).as("기록이 끝난 뒤 ACK").hasSize(1);
		verify(ai).generateAnswer(ChatTestFixtures.materialsFor("질문", sources));
		// 질문을 나누기 전까지는 원문을 하위 질문 1개(순번 0)로 저장하고 답변 시도와 연결합니다.
		verify(questions).saveTurn(1L, null, saved.getFirst().getId(), "질문",
				List.of(new SubQuestionFaqs("질문", sources, "질문")), "생성된 답변", (RegionSido) null);
		verify(faqLogs).saveAll(argThat(items -> {
			var list = new ArrayList<com.ubot.faq.entity.FaqLog>();
			items.forEach(list::add);
			return list.size() == 2 && list.get(0).getQuestionLogId() == 10L
					&& list.get(0).getRank() == 1 && list.get(1).getRank() == 2
					&& list.get(0).getCreatedAt() != null
					&& list.get(0).getCreatedAt().equals(list.get(1).getCreatedAt());
		}));
	}

	@ParameterizedTest
	@ValueSource(doubles = {0.74, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
	void onlyFaqsMeetingThresholdArePassedToLlmAndLogged(double excludedScore) throws Exception {
		var first = new FaqSearchResponseDto(1L, "유심 재발급", "매장 방문", 0.9, Intent.GENERAL);
		var excluded = new FaqSearchResponseDto(2L, "관련 없는 질문", "제외할 답변", excludedScore, Intent.GENERAL);
		var third = new FaqSearchResponseDto(3L, "준비물", "준비물 안내", 0.75, Intent.GENERAL);
		var accepted = List.of(first, third);
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(first, excluded, third));
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", accepted))).thenReturn(new AiAnswer("생성된 답변", null, false));

		assertThat(completeRequest()).contains("\"status\":\"SUCCEEDED\"", "생성된 답변");
		verify(ai).generateAnswer(ChatTestFixtures.materialsFor("질문", accepted));
		verify(faqLogs).saveAll(argThat(items -> {
			var logs = new ArrayList<com.ubot.faq.entity.FaqLog>();
			items.forEach(logs::add);
			return logs.size() == 2
					&& logs.get(0).getFaq().getId().equals(first.faqId())
					&& logs.get(0).getRank() == 1 && logs.get(0).getSimilarity() == 0.9
					&& logs.get(1).getFaq().getId().equals(third.faqId())
					&& logs.get(1).getRank() == 2 && logs.get(1).getSimilarity() == 0.75;
		}));
		verify(faqs, never()).getReferenceById(excluded.faqId());
	}

	@Test void noResultsSkipLlmAndPersistFailure() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());
		assertThat(completeRequest(ChatErrorCode.NO_FAQ)).contains("검색 결과가 없습니다.", "\"status\":\"FAILED\"", "\"retryable\":true");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.NO_FAQ);
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@ParameterizedTest
	@ValueSource(doubles = {0.74, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
	void noFaqMeetingThresholdSkipsLlmAndSuccessLogs(double score) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(1L, "q", "a", score, Intent.GENERAL),
				new FaqSearchResponseDto(2L, "q2", "a2", 0.7, Intent.GENERAL)));
		assertThat(completeRequest(ChatErrorCode.INSUFFICIENT_FAQ)).contains("정확한 답변을 찾지 못했습니다.", "\"status\":\"FAILED\"", "\"retryable\":true");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test void thresholdEqualityStillCallsLlm() throws Exception {
		var sources = List.of(new FaqSearchResponseDto(1L, "q", "a", 0.75, Intent.GENERAL));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenReturn(new AiAnswer("답변", null, false));
		assertThat(completeRequest()).contains("\"status\":\"SUCCEEDED\"");
	}

	@Test void configuredSearchSizeAndThresholdAreUsed() throws Exception {
		ReflectionTestUtils.setField(processor, "topK", 5);
		ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.8);
		when(vector.getSimilarList("질문", 5)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.79, Intent.GENERAL)));

		assertThat(completeRequest(ChatErrorCode.INSUFFICIENT_FAQ)).contains("\"status\":\"FAILED\"", "\"retryable\":true");
		verify(vector).getSimilarList("질문", 5);
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
		verifyNoInteractions(ai);
	}

	@ParameterizedTest @EnumSource(LlmErrorCode.class)
	void llmExceptionsAreRecordedAndExposeOnlySafeMessages(LlmErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL)));
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenThrow(new LlmException(code, new RuntimeException("secret")));
		assertThat(completeRequest(code)).contains("\"status\":\"FAILED\"", "\"retryable\":true")
				.contains(code.getMessage(), saved.getFirst().getIdempotencyKey()).doesNotContain("secret");
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(code);
		assertThat(saved.getFirst().getErrorCode().getCode()).isEqualTo(code.getCode());
		assertThat(saved.getFirst().getErrorMessage()).isEqualTo(code.getMessage());
		verifyNoInteractions(questions, faqLogs);
	}

	@ParameterizedTest @EnumSource(EmbeddingErrorCode.class)
	void embeddingExceptionsAreRecorded(EmbeddingErrorCode code) throws Exception {
		when(vector.getSimilarList("질문", 3)).thenThrow(new EmbeddingException(code));
		assertThat(completeRequest(code)).contains("\"status\":\"FAILED\"", "\"retryable\":true", code.getMessage());
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(code);
		assertThat(saved.getFirst().getErrorMessage()).isEqualTo(code.getMessage());
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@Test void missingPromptBecomesRecordedFailureAndAllowsRetry() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL)));
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenThrow(new PromptException(PromptErrorCode.PROMPT_NOT_READY));
		assertThat(completeRequest(PromptErrorCode.PROMPT_NOT_READY)).contains("\"status\":\"FAILED\"", "\"retryable\":true",
				PromptErrorCode.PROMPT_NOT_READY.getMessage());
		assertThat(saved.getFirst().getErrorCode()).isEqualTo(PromptErrorCode.PROMPT_NOT_READY);
		assertThat(attemptsService.validateRetryAttempt(saved.getFirst())).isEmpty();
		service.retryChat(1L, saved.getFirst().getIdempotencyKey(), null);
		assertThat(saved).hasSize(2);
		assertThat(saved.getLast().getAttemptCount()).isEqualTo(2);
		assertThat(saved.getLast().getStatus()).isEqualTo("PENDING");
		assertThat(harness.jobs).hasSize(2);
		assertThat(harness.jobs.getLast().getStatus()).as("재시도는 새 작업으로 접수").isEqualTo(ChatJobStatus.QUEUED);
		assertThat(harness.jobs.getLast().getAttemptId()).isEqualTo(saved.getLast().getId());
	}

	@Test void pendingAndSucceededAttemptsCannotStartAnotherGeneration() {
		service.createChat(1L, null, "질문", null, null);
		String key = saved.getFirst().getIdempotencyKey();
		assertThat(attemptsService.validateRetryAttempt(saved.getFirst())).contains(ChatErrorCode.PROCESSING);
		assertChatError(() -> service.retryChat(1L, key, null), ChatErrorCode.PROCESSING);
		saved.getFirst().succeed();
		assertThat(attemptsService.validateRetryAttempt(saved.getFirst())).contains(ChatErrorCode.ALREADY_SUCCEEDED);
		assertChatError(() -> service.retryChat(1L, key, null), ChatErrorCode.ALREADY_SUCCEEDED);
		assertThat(saved).hasSize(1);
		assertThat(harness.jobs).hasSize(1);
	}

	@ParameterizedTest @ValueSource(ints = {1, 2, 3})
	void configuredAttemptLimitKeepsKeyAndBlocksNextAttempt(int maxAttempts) {
		ReflectionTestUtils.setField(attemptsService, "maxAttempts", maxAttempts);
		when(vector.getSimilarList("질문", 3)).thenThrow(new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT));
		service.createChat(1L, null, "질문", null, null);
		assertThat(harness.runQueuedJobs()).isEqualTo(1);
		String key = saved.getFirst().getIdempotencyKey();
		for (int count = 2; count <= maxAttempts; count++) {
			service.retryChat(1L, key, null);
			assertThat(harness.runQueuedJobs()).isEqualTo(1);
		}
		assertThat(saved).extracting(AnswerAttemptsHistory::getAttemptCount)
				.containsExactlyElementsOf(IntStream.rangeClosed(1, maxAttempts).boxed().toList());
		assertThat(saved).allMatch(a -> a.getIdempotencyKey().equals(key) && a.getStatus().equals("FAIL"));
		assertThat(harness.jobs).hasSize(maxAttempts).allMatch(job -> job.getStatus() == ChatJobStatus.FAILED);
		assertChatError(() -> service.retryChat(1L, key, null), ChatErrorCode.LIMIT_REACHED);
		assertThat(saved).hasSize(maxAttempts);
	}

	@Test void attemptsRecordModelNameReportedByLlmService() {
		var first = attemptsService.createAnswerAttempt(1L, "질문");
		attemptsService.recordFailure(first, ChatErrorCode.NO_FAQ);
		var retry = attemptsService.createRetryAttempt(1L, first.getIdempotencyKey());
		assertThat(List.of(first, retry)).extracting(AnswerAttemptsHistory::getLlmModel)
				.containsOnly("recorded-chat-model");
	}

	@Test void attemptsRecordModelNameReportedByEmbeddingService() {
		var first = attemptsService.createAnswerAttempt(1L, "질문");
		attemptsService.recordFailure(first, ChatErrorCode.NO_FAQ);
		var retry = attemptsService.createRetryAttempt(1L, first.getIdempotencyKey());
		assertThat(List.of(first, retry)).extracting(AnswerAttemptsHistory::getEmbeddingModel)
				.containsOnly("recorded-embedding-model");
	}

	@Test void retryRejectsUnknownKey() {
		assertChatError(() -> service.retryChat(1L, "a".repeat(64), null), ChatErrorCode.ATTEMPT_NOT_FOUND);
		assertThat(saved).isEmpty();
		assertThat(harness.jobs).isEmpty();
		verifyNoInteractions(vector, ai);
	}

	@ParameterizedTest
	@EnumSource(value = ChatErrorCode.class, names = {
			"NO_FAQ", "INSUFFICIENT_FAQ", "PROMPT_NOT_READY", "INTERNAL_ERROR", "STORAGE_UNAVAILABLE", "TASK_START_FAILED"
	})
	void failedAttemptsCanRetryUntilThirdAttempt(ChatErrorCode code) {
		var attempt = attemptsService.createAnswerAttempt(1L, "질문");
		String key = attempt.getIdempotencyKey();
		for (int count = 1; count <= 3; count++) {
			assertThat(attemptsService.recordFailure(attempt, code)).isTrue();
			assertThat(attempt.getAttemptCount()).isEqualTo(count);
			assertThat(attemptsService.validateRetryAttempt(attempt))
					.isEqualTo(count < 3 ? Optional.empty() : Optional.of(ChatErrorCode.LIMIT_REACHED));
			if (count < 3) {
				attempt = attemptsService.createRetryAttempt(1L, key);
			}
		}
		assertChatError(() -> attemptsService.createRetryAttempt(1L, key), ChatErrorCode.LIMIT_REACHED);
		assertThat(saved).hasSize(3).allSatisfy(stored -> {
			assertThat(stored.getIdempotencyKey()).isEqualTo(key);
			assertThat(stored.getErrorCode()).isEqualTo(code);
		});
		verifyNoInteractions(vector, ai);
	}

	@Test
	void failureStorageErrorLeavesJobRunningForRecoveryWithoutExposingIt() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of());
		var post = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}")).andExpect(status().isAccepted()).andReturn();
		// 작업을 가져올 땐 시도를 찾고, 결과 기록 때 시도 잠금에서 DB가 실패함
		when(attempts.findAttemptForLock(anyLong()))
				.thenThrow(new DataAccessResourceFailureException("database secret"));

		assertThat(harness.runQueuedJobs()).isEqualTo(1);

		ChatJob job = harness.jobs.getFirst();
		assertThat(job.getStatus()).as("기록하지 못해 RUNNING으로 남음").isEqualTo(ChatJobStatus.RUNNING);
		assertThat(harness.acked).as("ACK하지 않아 메시지가 처리 대기로 남음").isEmpty();
		assertThat(saved.getFirst().getStatus()).isEqualTo("PENDING");
		String body = mvc.perform(get("/chat/jobs/{jobId}", job.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("RUNNING"))
				.andExpect(jsonPath("$.data.retryable").value(false))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("database secret");
		assertThat(post.getResponse().getContentAsString()).doesNotContain("database secret");
		verifyNoInteractions(ai, questions, faqLogs);
	}

	@ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"not-a-key"})
	void invalidRetryKeyDoesNotTouchDb(String key) {
		assertChatError(() -> service.retryChat(1L, key, null), ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		verifyNoInteractions(attempts);
	}

	@Test void unexpectedExceptionUsesRegisteredErrorCodeWithoutExposingCause() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL)));
		when(ai.generateAnswer(any(AnswerMaterials.class))).thenThrow(new IllegalStateException("secret"));

		assertThat(completeRequest(ChatErrorCode.INTERNAL_ERROR)).contains("\"status\":\"FAILED\"", "\"retryable\":true",
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
		assertThat(harness.jobs).isEmpty();
		verifyNoInteractions(vector, ai);
	}

	@ParameterizedTest
	@ValueSource(strings = {"SUCCESS", "FAIL"})
	void completedAttemptRejectsLateSuccessLogsAndFailure(String status) {
		service.createChat(1L, null, "질문", null, null);
		var stored = saved.getFirst();
		if ("SUCCESS".equals(status)) {
			stored.succeed();
		} else {
			stored.fail(ChatErrorCode.RESPONSE_TIMEOUT);
		}
		var previousError = stored.getErrorCode();
		var previousMessage = stored.getErrorMessage();

		assertThat(attemptsService.recordSuccess(stored, "늦게 도착한 답변",
				List.of(new SubQuestionFaqs("질문", List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL)), "질문")),
				null)).isEmpty();
		assertThat(attemptsService.recordFailure(stored, ChatErrorCode.INTERNAL_ERROR)).isFalse();

		assertThat(stored.getStatus()).isEqualTo(status);
		assertThat(stored.getErrorCode()).isEqualTo(previousError);
		assertThat(stored.getErrorMessage()).isEqualTo(previousMessage);
		verifyNoInteractions(questions, faqLogs, faqs);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3, 5})
	void timeoutRetriesRespectConfiguredAttemptLimit(int maxAttempts) {
		ReflectionTestUtils.setField(attemptsService, "maxAttempts", maxAttempts);
		service.createChat(1L, null, "질문", null, null);
		String key = saved.getFirst().getIdempotencyKey();

		for (int count = 1; count <= maxAttempts; count++) {
			var attempt = saved.getLast();
			attempt.fail(ChatErrorCode.RESPONSE_TIMEOUT);
			assertThat(attempt.getAttemptCount()).isEqualTo(count);
			assertThat(attemptsService.validateRetryAttempt(attempt))
					.isEqualTo(count < maxAttempts ? Optional.empty() : Optional.of(ChatErrorCode.LIMIT_REACHED));
			if (count < maxAttempts) {
				service.retryChat(1L, key, null);
			}
		}

		assertChatError(() -> service.retryChat(1L, key, null), ChatErrorCode.LIMIT_REACHED);
		assertThat(saved).hasSize(maxAttempts).allSatisfy(attempt ->
				assertThat(attempt.getIdempotencyKey()).isEqualTo(key));
	}

	@Test
	void timeoutRetryStillRejectsAnotherUsersKey() {
		service.createChat(1L, null, "질문", null, null);
		var attempt = saved.getFirst();
		attempt.fail(ChatErrorCode.RESPONSE_TIMEOUT);

		assertChatError(() -> service.retryChat(2L, attempt.getIdempotencyKey(), null),
				ChatErrorCode.ATTEMPT_NOT_FOUND);
		assertThat(saved).hasSize(1);
		verifyNoInteractions(vector, ai);
	}

	@Test void guestAttemptIsStoredWithConversationOnlyAndSuccessLogKeepsIt() {
		allowGuestQuestions(5);
		var sources = List.of(new FaqSearchResponseDto(1L, "q", "a", 0.9, Intent.GENERAL));
		when(vector.getSimilarList("질문", 3)).thenReturn(sources);
		when(ai.generateAnswer(ChatTestFixtures.materialsFor("질문", sources))).thenReturn(new AiAnswer("생성된 답변", null, false));

		var submission = service.createGuestChat(7L, "질문", null, null);
		assertThat(harness.runQueuedJobs()).isEqualTo(1);

		assertThat(harness.guestStatus(submission, 7L).status()).isEqualTo(ChatJobStatus.SUCCEEDED);
		var attempt = saved.getFirst();
		assertThat(attempt.getStatus()).isEqualTo("SUCCESS");
		assertThat(attempt.getUserId()).isNull();
		assertThat(attempt.getConversationId()).isEqualTo(7L);
		assertThat(attempt.getIdempotencyKey()).isEqualTo(
				ChatAttemptsService.createGuestIdempotencyKey(7L, "질문", attempt.getCreatedAt())).hasSize(64);
		assertThat(ChatAttemptsService.createGuestIdempotencyKey(8L, "질문", attempt.getCreatedAt()))
				.isNotEqualTo(attempt.getIdempotencyKey());
		verify(questions).saveTurn(null, 7L, attempt.getId(), "질문",
				List.of(new SubQuestionFaqs("질문", sources, "질문")), "생성된 답변", (RegionSido) null);
		assertChatError(() -> harness.guestStatus(submission, 8L), ChatErrorCode.JOB_NOT_FOUND);
		assertChatError(() -> harness.memberStatus(submission, 1L), ChatErrorCode.JOB_NOT_FOUND);
	}

	@Test void guestQuestionLimitCountsAnswersAndMissingFaqButNotSystemFailures() {
		allowGuestQuestions(2);
		service.createGuestChat(7L, "시스템 오류 질문", null, null);
		var systemFailure = saved.getLast();
		systemFailure.fail(ChatErrorCode.RESPONSE_TIMEOUT);
		service.createGuestChat(7L, "답변된 질문", null, null);
		saved.getLast().succeed();
		service.createGuestChat(7L, "FAQ 없는 질문", null, null);
		var missingFaq = saved.getLast();
		missingFaq.fail(ChatErrorCode.NO_FAQ);

		assertChatError(() -> service.createGuestChat(7L, "넷째 질문", null, null),
				ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED);
		assertThat(saved).hasSize(3);
		assertThat(harness.jobs).as("거절된 질문은 작업도 남기지 않음").hasSize(3);

		service.retryGuestChat(7L, systemFailure.getIdempotencyKey(), null);
		assertThat(saved).hasSize(4);
		assertThat(saved.getLast().getAttemptCount()).isEqualTo(2);
		saved.getLast().succeed();

		service.retryGuestChat(7L, missingFaq.getIdempotencyKey(), null);
		assertThat(saved).hasSize(5);
		assertThat(saved.getLast().getAttemptCount()).isEqualTo(2);
		assertThat(saved.getLast().getUserId()).isNull();
		assertThat(saved.getLast().getConversationId()).isEqualTo(7L);
	}

	@Test void guestRetryRejectsAnotherConversationsKey() {
		allowGuestQuestions(5);
		service.createGuestChat(7L, "질문", null, null);
		var attempt = saved.getFirst();
		attempt.fail(ChatErrorCode.RESPONSE_TIMEOUT);

		assertChatError(() -> service.retryGuestChat(8L, attempt.getIdempotencyKey(), null),
				ChatErrorCode.ATTEMPT_NOT_FOUND);
		assertThat(saved).hasSize(1);
	}

	private Optional<AnswerAttemptsHistory> findSaved(Long attemptId) {
		return saved.stream().filter(a -> a.getId().equals(attemptId)).findFirst();
	}

	private void allowGuestQuestions(int maxQuestionCount) {
		GuestChatSettings settings = mock(GuestChatSettings.class);
		when(settings.getMaxQuestionCount()).thenReturn(maxQuestionCount);
		when(guestSettings.findById(GuestChatSettings.SETTINGS_ID)).thenReturn(Optional.of(settings));
		when(conversations.findConversationForLock(anyLong())).thenAnswer(call -> Optional.of(Conversation.createGuest()));
		when(attempts.countGuestQuestions(anyLong(), anyString(), any(), any())).thenAnswer(call ->
				saved.stream().filter(a -> call.getArgument(0).equals(a.getConversationId())
						&& !a.getIdempotencyKey().equals(call.getArgument(1))
						&& (!"FAIL".equals(a.getStatus()) || a.getErrorCode() == call.getArgument(2)
								|| a.getErrorCode() == call.getArgument(3)))
						.map(AnswerAttemptsHistory::getIdempotencyKey).distinct().count());
		when(attempts.findGuestInitialAttemptsForLock(anyLong(), anyString(), eq(1))).thenAnswer(call ->
				saved.stream().filter(a -> a.getUserId() == null
						&& call.getArgument(0).equals(a.getConversationId())
						&& a.getIdempotencyKey().equals(call.getArgument(1))
						&& a.getAttemptCount() == 1).findFirst());
		when(attempts.findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
				anyLong(), anyString())).thenAnswer(call -> saved.stream().filter(a -> a.getUserId() == null
						&& call.getArgument(0).equals(a.getConversationId())
						&& a.getIdempotencyKey().equals(call.getArgument(1)))
				.max(Comparator.comparingInt(AnswerAttemptsHistory::getAttemptCount)));
	}

	private String completeRequest() throws Exception {
		return completeRequest(null);
	}

	// 접수 → Worker 처리 → 상태 조회 응답 본문
	private String completeRequest(ErrorCode expectedError) throws Exception {
		mvc.perform(post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.status").value("QUEUED"))
				.andExpect(jsonPath("$.data.answer").doesNotExist());
		verifyNoInteractions(vector, ai); // 답변 생성은 요청 스레드에서 실행하지 않습니다.
		assertThat(harness.runQueuedJobs()).isEqualTo(1);
		var result = mvc.perform(get("/chat/jobs/{jobId}", harness.jobs.getLast().getId()))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.status").value(expectedError == null ? "SUCCEEDED" : "FAILED"));
		if (expectedError == null) {
			result.andExpect(jsonPath("$.data.answer").isString());
		} else {
			result.andExpect(jsonPath("$.data.errorCode").value(expectedError.getCode()))
					.andExpect(jsonPath("$.data.errorMessage").value(expectedError.getMessage()));
		}
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private void assertChatError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ChatErrorCode code) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChatException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}
}
