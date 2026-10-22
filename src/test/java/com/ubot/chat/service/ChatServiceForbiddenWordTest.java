package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("채팅 금지어 차단 테스트")
class ChatServiceForbiddenWordTest {
	private final FaqVectorService vector = ChatTestFixtures.vectorService();
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
	private final ForbiddenWordFilterService filter = mock(ForbiddenWordFilterService.class);
	private ChatJobTestHarness harness = new ChatJobTestHarness(attempts, ChatTestFixtures.processor(FaqRetrievalService.denseOnly(vector), ai, ChatTestFixtures.collector(), ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter(), ChatTestFixtures.inputGuard()));
	private final ChatService service = new ChatService(attempts, ChatTestFixtures.inputGuard(filter), harness.submissionService,
			ipRegionResolver);

	@Test
	@DisplayName("금지어가 포함된 질문은 기록·작업 접수·임베딩·검색·LLM 호출 없이 차단한다")
	void blocksBeforeAnyProcessing() {
		doThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED))
				.when(filter).validateForbiddenWord("너 바보야");

		assertThatThrownBy(() -> service.createChat(1L, null, "너 바보야", null, null))
				.isInstanceOfSatisfying(ForbiddenWordException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));

		verifyNoInteractions(attempts, vector, ai);
		assertThat(harness.jobs).isEmpty();
	}

	@Test
	@DisplayName("금지어가 없으면 필터를 통과해 작업으로 접수한다")
	void queuesWhenPassed() {
		AnswerAttemptsHistory attempt = mock(AnswerAttemptsHistory.class);
		when(attempts.createAnswerAttempt(1L, null, "요금제 알려줘")).thenReturn(attempt);

		service.createChat(1L, null, "요금제 알려줘", null, null);

		verify(filter, atLeastOnce()).validateForbiddenWord("요금제 알려줘");
		verify(attempts).createAnswerAttempt(1L, null, "요금제 알려줘");
		assertThat(harness.jobs).hasSize(1);
	}

	@Test
	@DisplayName("빈 질문은 금지어 검사 전에 기본 검증에서 거절한다")
	void blankQuestionFailsBasicValidationFirst() {
		assertThatThrownBy(() -> service.createChat(1L, null, " ", null, null))
				.isInstanceOf(com.ubot.chat.exception.ChatException.class);

		verifyNoInteractions(filter);
	}


	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("회원·비회원 재시도도 현재 금지어 검사에서 차단되면 새 시도를 만들지 않는다")
	void blocksStoredForbiddenWordBeforeCreatingRetry(boolean guest) {
		String key = "a".repeat(64);
		AnswerAttemptsHistory attempt = mock(AnswerAttemptsHistory.class);
		when(attempt.getQuestion()).thenReturn("너 바보야");
		if (guest) {
			when(attempts.getLatestGuestAnswerAttempt(7L, key)).thenReturn(attempt);
		} else {
			when(attempts.getLatestAnswerAttempt(1L, key)).thenReturn(attempt);
		}
		doThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED))
				.when(filter).validateForbiddenWord("너 바보야");

		assertThatThrownBy(() -> {
			if (guest) {
				service.retryGuestChat(7L, key, null);
			} else {
				service.retryChat(1L, key, null);
			}
		}).isInstanceOfSatisfying(ForbiddenWordException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));

		verify(attempts, never()).createRetryAttempt(1L, key);
		verify(attempts, never()).createGuestRetryAttempt(7L, key);
		verifyNoInteractions(vector, ai);
		assertThat(harness.jobs).noneSatisfy(job -> assertThat(job.getStatus()).isIn(ChatJobStatus.QUEUED, ChatJobStatus.RUNNING));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("회원·비회원 최초 질문의 변형 금지어는 시도 생성과 AI 작업 전에 차단한다")
	void blocksVariantBeforeCreatingInitialAttempt(boolean guest) {
		ChatService actualService = createServiceWithActiveForbiddenWord();
		String question = "너 진짜 바\u200B 보입니다.";

		assertThatThrownBy(() -> {
			if (guest) {
				actualService.createGuestChat(7L, question, null, null);
			} else {
				actualService.createChat(1L, null, question, null, null);
			}
		}).isInstanceOfSatisfying(ForbiddenWordException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));

		verifyNoInteractions(attempts, vector, ai);
		assertThat(harness.jobs).noneSatisfy(job -> assertThat(job.getStatus()).isIn(ChatJobStatus.QUEUED, ChatJobStatus.RUNNING));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("저장된 질문의 변형 금지어도 재시도 횟수를 늘리기 전에 차단한다")
	void blocksStoredVariantBeforeCreatingRetry(boolean guest) {
		ChatService actualService = createServiceWithActiveForbiddenWord();
		String key = "a".repeat(64);
		AnswerAttemptsHistory attempt = mock(AnswerAttemptsHistory.class);
		when(attempt.getQuestion()).thenReturn("너 진짜 바\u200B 보라고요");
		if (guest) {
			when(attempts.getLatestGuestAnswerAttempt(7L, key)).thenReturn(attempt);
		} else {
			when(attempts.getLatestAnswerAttempt(1L, key)).thenReturn(attempt);
		}

		assertThatThrownBy(() -> {
			if (guest) {
				actualService.retryGuestChat(7L, key, null);
			} else {
				actualService.retryChat(1L, key, null);
			}
		}).isInstanceOfSatisfying(ForbiddenWordException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));

		verify(attempts, never()).createRetryAttempt(1L, key);
		verify(attempts, never()).createGuestRetryAttempt(7L, key);
		verifyNoInteractions(vector, ai);
		assertThat(harness.jobs).noneSatisfy(job -> assertThat(job.getStatus()).isIn(ChatJobStatus.QUEUED, ChatJobStatus.RUNNING));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("검사를 통과한 질문은 전각·숨은 문자·공백을 포함한 원문 그대로 기록에 전달한다")
	void preservesOriginalQuestionWhenPassed(boolean guest) {
		ChatService actualService = createServiceWithActiveForbiddenWord();
		String question = "ＵＳＩＭ\u200B\t재발급 방법을 알려줘.";
		AnswerAttemptsHistory attempt = mock(AnswerAttemptsHistory.class);
		if (guest) {
			when(attempts.createGuestAnswerAttempt(7L, question)).thenReturn(attempt);
			actualService.createGuestChat(7L, question, null, null);
			verify(attempts).createGuestAnswerAttempt(7L, question);
		} else {
			when(attempts.createAnswerAttempt(1L, null, question)).thenReturn(attempt);
			actualService.createChat(1L, null, question, null, null);
			verify(attempts).createAnswerAttempt(1L, null, question);
		}

		assertThat(harness.jobs).hasSize(1);
	}

	private ChatService createServiceWithActiveForbiddenWord() {
		ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
		when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
				ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
		ForbiddenWordFilterService actualFilter = new ForbiddenWordFilterService(repository);
		actualFilter.refreshForbiddenWordCache();
		var guard = ChatTestFixtures.inputGuard(actualFilter);
		var processor = ChatTestFixtures.processor(FaqRetrievalService.denseOnly(vector), ai, ChatTestFixtures.collector(), ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter(), guard);
		harness = new ChatJobTestHarness(attempts, processor);
		return new ChatService(attempts, guard, harness.submissionService, ipRegionResolver);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("금지어와 정상 질문이 섞여도 접수 전에 전체 차단한다")
	void rejectsMixedForbiddenQuestionBeforeStarting(boolean guest) {
        ChatService actualService = createServiceWithActiveForbiddenWord();
        String original = "안녕 😊 바보야. 요금제 알려줘";
        assertThatThrownBy(() -> {
            if (guest) actualService.createGuestChat(7L, original, null, null);
            else actualService.createChat(1L, null, original, null, null);
        }).isInstanceOf(ForbiddenWordException.class);
        assertThat(harness.jobs).isEmpty();
        verifyNoInteractions(attempts, vector, ai);
    }
}
