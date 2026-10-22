package com.ubot.chat.service;

import com.ubot.ai.service.AiGuardModelService;
import com.ubot.ai.service.AiGuardTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.chat.job.dto.response.ChatJobStatusResponseDto;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.service.ChatJobQueryService;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.worker.ChatJobWorker;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.conversation.service.ConversationService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.service.FaqSearchMockSupport;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.outbox.OutboxWorker;
import com.ubot.prompt.format.SubQuestionFormat;
import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.pgvector.PGvector;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 판정 LLM부터 하위 질문별 검색·답변 생성·질문 로그·미답변·대화 기록까지 실제 서비스와 임시 PostgreSQL로 확인합니다.
 * FAQ 검색 결과, 임베딩, 외부 LLM 통신만 mock입니다.
 */
@SpringBootTest(properties = {
		"prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
		"prompt.faq.user-location=classpath:prompts/test-faq-user.txt",
		"CHAT_SUB_QUESTION_ENABLED=true",
		"CHAT_TOP_K=3",
		"CHAT_CONFIDENCE_THRESHOLD=0.75"})
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("질문 분리·멀티턴 채팅 통합 테스트")
class SubQuestionChatIntegrationTest {
    @MockitoBean AiGuardModelService guardModel;

	@Autowired ChatService chatService;
	@Autowired ChatJobWorker worker;
	@Autowired ChatJobQueryService jobQuery;
	@Autowired StringRedisTemplate redis;
	@Autowired ConversationService conversationService;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired QuestionLogRepository questionLogs;
	@Autowired FaqLogRepository faqLogs;
	@Autowired UnansweredQuestionRepository unansweredQuestions;
	@Autowired UnansweredQuestionGroupRepository unansweredGroups;
	@Autowired OutboxWorker outboxWorker;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean FaqVectorService vector;
	@MockitoBean FaqSentenceFilterService sentenceFilter;
	@MockitoBean LlmClient llm;
	@MockitoBean EmbeddingService embeddingService;
	@MockitoBean EmbeddingProfileService embeddingProfileService;

	Long userId;
	Long conversationId;
	UUID conversationPublicId;
	Long reissueFaqId;

	@BeforeEach
	void setUp() {
        AiGuardTestSupport.allow(guardModel);
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:result:*"));
		redis.delete(redis.keys("chat:jobs:published:*"));
		jdbc.update("DELETE FROM outbox_events");
		unansweredQuestions.deleteAllInBatch();
		unansweredGroups.deleteAllInBatch();
		faqLogs.deleteAllInBatch();
		questionLogs.deleteAllInBatch();
		jdbc.update("DELETE FROM chat_jobs");
		attempts.deleteAllInBatch();
		userId = jdbc.queryForObject(
				"INSERT INTO users (email, password_hash, name) VALUES (?, 'hash', '테스터') RETURNING user_id",
				Long.class, UUID.randomUUID() + "@test.com");
		conversationId = jdbc.queryForObject(
				"INSERT INTO conversations (user_id, type) VALUES (?, 'MEMBER') RETURNING conversation_id",
				Long.class, userId);
		conversationPublicId = conversationService.getPublicId(conversationId);
		Long category = jdbc.queryForObject(
				"INSERT INTO faq_category (name) VALUES (?) RETURNING id", Long.class, UUID.randomUUID().toString());
		reissueFaqId = jdbc.queryForObject("INSERT INTO faq (category_id, question, answer) VALUES (?, ?, ?) RETURNING id",
				Long.class, category, "유심 재발급 방법", "매장 방문");

		Long profileId = jdbc.queryForObject("""
				INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
				VALUES ('ollama', 'bge-m3:567m', 1024, 1)
				ON CONFLICT (provider, model_name, dimensions, profile_version) DO UPDATE SET provider = EXCLUDED.provider
				RETURNING profile_id
				""", Long.class);
		when(embeddingProfileService.getCurrentProfileId()).thenReturn(profileId);
		when(embeddingService.embedText(anyString())).thenReturn(new PGvector(new float[1024]));
		when(vector.getSimilarList(anyString(), anyInt())).thenReturn(List.of());
		FaqSearchMockSupport.bridgeSearch(vector);
		FaqSearchMockSupport.passAnswers(sentenceFilter);
	}

	@Test
	@DisplayName("나눈 질문은 하위 질문마다 질문 로그를 남기고, 근거를 못 찾은 하위 질문만 미답변으로 기록하며, 대화 기록에는 한 번만 보인다")
	void splitQuestionIsStoredPerSubQuestion() {
		judgeReturns("유심 재발급 방법", "해외 로밍 요금");
		when(vector.getSimilarList("유심 재발급 방법", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(reissueFaqId, "유심 재발급 방법", "매장 방문", 0.9, Intent.GENERAL)));
		answerReturns("재발급은 매장에서 가능하고, 로밍 요금은 확인할 수 없습니다.");

		var response = ask("유심 재발급 방법이랑 해외 로밍 요금 알려줘");

		assertThat(response.status()).isEqualTo(ChatJobStatus.SUCCEEDED);
		List<QuestionLog> logs = questionLogs.findAll().stream()
				.sorted(Comparator.comparingInt(QuestionLog::getSubQuestionIndex)).toList();
		assertThat(logs).extracting(QuestionLog::getUserQuestion, QuestionLog::getSubQuestion,
						QuestionLog::getSubQuestionIndex, QuestionLog::getAnswer)
				.containsExactly(
						tuple("유심 재발급 방법이랑 해외 로밍 요금 알려줘", "유심 재발급 방법", 1,
								"재발급은 매장에서 가능하고, 로밍 요금은 확인할 수 없습니다."),
						tuple("유심 재발급 방법이랑 해외 로밍 요금 알려줘", "해외 로밍 요금", 2,
								"재발급은 매장에서 가능하고, 로밍 요금은 확인할 수 없습니다."));
		assertThat(faqLogs.findAll()).singleElement().satisfies(faqLog -> {
			assertThat(faqLog.getQuestionLogId()).isEqualTo(logs.getFirst().getId());
			assertThat(faqLog.getRank()).isEqualTo(1);
		});
		outboxWorker.processPending();
		assertThat(unansweredQuestions.findAll()).extracting(
						UnansweredQuestion::getSubQuestionIndex, UnansweredQuestion::getSubQuestion)
				.containsExactly(tuple(2, "해외 로밍 요금"));
		// 답변 LLM에는 원문과 함께 하위 질문별 근거가 가고, 제목은 검색어가 아니라 원문 조각입니다.
		assertThat(answerRequest().messages().getLast().content())
				.contains("유심 재발급 방법이랑 해외 로밍 요금 알려줘", "## 하위 질문 1: 유심 재발급 방법이랑",
						"[FAQ ID: " + reissueFaqId + "]", "## 하위 질문 2: 해외 로밍 요금 알려줘");
		assertThat(conversationService.getConversationMessageList(userId, conversationPublicId))
				.singleElement().satisfies(message -> {
					assertThat(message.question()).isEqualTo("유심 재발급 방법이랑 해외 로밍 요금 알려줘");
					assertThat(message.answer()).isEqualTo("재발급은 매장에서 가능하고, 로밍 요금은 확인할 수 없습니다.");
				});
	}

	@Test
	@DisplayName("다음 질문의 판정에는 앞선 턴의 하위 질문과 답변이 이전 대화로 들어가고, 다시 쓴 질문으로 검색하며, 멀티턴이면 답변에도 이전 대화를 넣는다")
	void nextQuestionUsesPreviousTurn() {
		judgeReturns("유심 재발급 방법");
		when(vector.getSimilarList("유심 재발급 방법", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(reissueFaqId, "유심 재발급 방법", "매장 방문", 0.9, Intent.GENERAL)));
		answerReturns("매장에서 재발급할 수 있습니다.");
		ask("유심 재발급 방법 알려줘");

		judgeReturns("유심 재발급 비용");
		when(vector.getSimilarList("유심 재발급 비용", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(reissueFaqId, "유심 재발급 방법", "매장 방문", 0.8, Intent.GENERAL)));
		answerReturns("비용 안내입니다.");
		var response = ask("그럼 비용은?");

		assertThat(response.status()).isEqualTo(ChatJobStatus.SUCCEEDED);
		ArgumentCaptor<LlmRequestDto> requests = ArgumentCaptor.forClass(LlmRequestDto.class);
		verify(guardModel, org.mockito.Mockito.atLeastOnce()).generate(requests.capture());
		LlmRequestDto lastJudge = requests.getAllValues().stream().filter(this::isJudge).reduce((a, b) -> b).orElseThrow();
		var data = tools.jackson.databind.json.JsonMapper.builder().build()
				.readTree(lastJudge.messages().getLast().content());
		assertThat(data.get("history").get(0).get("question").asString()).contains("유심 재발급 방법");
		assertThat(data.get("history").get(0).get("answer").asString()).isEqualTo("매장에서 재발급할 수 있습니다.");
		assertThat(data.get("current_question").asString()).isEqualTo("그럼 비용은?");
		verify(vector).getSimilarList("유심 재발급 비용", 3);
		verify(vector, never()).getSimilarList("그럼 비용은?", 3);
		// '그럼'으로 이어 물은 질문이라 답변 LLM에 앞선 턴(사용자 원문과 답변)이 메시지로 들어갑니다.
		assertThat(answerRequest().messages()).extracting(message -> message.role(), message -> message.content())
				.contains(tuple(com.ubot.llm.enums.LlmMessageRole.USER, "유심 재발급 방법 알려줘"),
						tuple(com.ubot.llm.enums.LlmMessageRole.ASSISTANT, "매장에서 재발급할 수 있습니다."));
	}

	@Test
	@DisplayName("하위 질문이 4개 이상이면 검색 없이 실패하고, 미답변·질문 로그를 남기지 않으며 재시도할 수 없다")
	void rejectsTooManySubQuestions() {
		judgeReturns("a", "b", "c", "d");

		// 나열 표지(쉼표)가 있어 원문 검색 없이 바로 판정합니다.
		var response = ask("a, b, c, d");

		assertThat(response.status()).isEqualTo(ChatJobStatus.FAILED);
		assertThat(response.errorCode()).isEqualTo(ChatErrorCode.TOO_MANY_SUB_QUESTIONS.getCode());
		assertThat(response.errorMessage()).isEqualTo("너무 많은 질문을 입력하셨습니다. 하나씩 물어봐주세요.");
		assertThat(response.retryable()).isFalse();
		var attempt = attempts.findAll().getFirst();
		assertThat(attempt.getErrorCode()).isEqualTo(ChatErrorCode.TOO_MANY_SUB_QUESTIONS);
		assertThat(questionLogs.findAll()).isEmpty();
		assertThat(unansweredQuestions.findAll()).isEmpty();
		verify(vector, never()).getSimilarList(anyString(), anyInt());
		assertThatThrownBy(() -> chatService.retryChat(userId, attempt.getIdempotencyKey(), null))
				.isInstanceOfSatisfying(ChatException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ChatErrorCode.RETRY_NOT_ALLOWED));
	}

	@Test
	@DisplayName("통합 안전 판정이 실패하면 원문 검색으로 우회하지 않는다")
    void failsClosedWhenIntegratedGuardTimesOut() {
        when(guardModel.generate(argThat(this::isJudge))).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
        var response = ask("유심 재발급이랑 비용");
        assertThat(response.status()).isEqualTo(ChatJobStatus.FAILED);
        assertThat(response.errorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT.getCode());
        assertThat(questionLogs.findAll()).isEmpty();
        verify(vector, never()).getSimilarList(anyString(), anyInt());
        verify(llm, never()).generateAnswer(org.mockito.ArgumentMatchers.any());
    }

	/** 질문을 접수하고 Worker가 처리한 뒤의 작업 상태를 돌려줍니다. 테스트 프로필은 Worker가 스스로 가져가지 않습니다. */
	private ChatJobStatusResponseDto ask(String question) {
		var submission = chatService.createChat(userId, conversationId, question, null, null);
		worker.processAvailableJobs();
		return jobQuery.getStatus(submission.response().jobId(), userId, Optional.empty());
	}

	private void judgeReturns(String... subQuestions) {
		String json = "{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\": ["
				+ String.join(", ", java.util.Arrays.stream(subQuestions).map(q -> "\"" + q + "\"").toList()) + "]}";
		when(guardModel.generate(argThat(this::isJudge))).thenReturn(new LlmResponseDto(json));
	}

	private void answerReturns(String answer) {
		when(llm.generateAnswer(argThat(request -> request != null && !isJudge(request))))
				.thenReturn(new LlmResponseDto(answer));
	}

	private LlmRequestDto answerRequest() {
		ArgumentCaptor<LlmRequestDto> requests = ArgumentCaptor.forClass(LlmRequestDto.class);
		verify(llm, org.mockito.Mockito.atLeastOnce()).generateAnswer(requests.capture());
		return requests.getAllValues().stream().filter(request -> !isJudge(request)).reduce((a, b) -> b).orElseThrow();
	}

	private boolean isJudge(LlmRequestDto request) {
		return request != null && request.generationOptions() != null
                && "input_safety_and_planning".equals(request.generationOptions().schemaName());
	}
}
