package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.guest.repository.GuestChatSettingsRepository;
import com.ubot.llm.service.LlmService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("답변 시도 질문 개인정보 마스킹 테스트")
class ChatAttemptsServiceMaskingTest {
	private static final String RAW = "01012345678 번호 해지하고 싶어요";
	private static final String MASKED = "010-****-5678 번호 해지하고 싶어요";
	private static final String KEY = "k".repeat(64);

	private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	private final ChatAttemptsService service = new ChatAttemptsService(
			attempts, mock(QuestionLogService.class), mock(FaqLogRepository.class), mock(FaqRepository.class),
			mock(ConversationRepository.class), mock(GuestChatSettingsRepository.class), mock(LlmService.class),
			mock(EmbeddingService.class), mock(PlatformTransactionManager.class));

	{
		ReflectionTestUtils.setField(service, "maxAttempts", 3);
		when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	@DisplayName("새 질문은 마스킹해서 저장한다")
	void masksNewQuestion() {
		assertThat(service.createAnswerAttempt(1L, 10L, RAW).getQuestion()).isEqualTo(MASKED);
	}

	@Test
	@DisplayName("원문으로 저장된 질문을 재시도하면 마스킹해서 저장한다")
	void masksRetryOfStoredRawQuestion() {
		AnswerAttemptsHistory failed = new AnswerAttemptsHistory(1L, RAW, 1, KEY, LocalDateTime.now(), "", "");
		failed.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
		when(attempts.findInitialAttemptsForLock(anyLong(), anyString(), anyInt())).thenReturn(Optional.of(failed));
		when(attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(1L, KEY))
				.thenReturn(Optional.of(failed));

		assertThat(service.createRetryAttempt(1L, KEY).getQuestion()).isEqualTo(MASKED);
	}

	@Test
	@DisplayName("원문으로 저장된 질문을 재검색하면 마스킹해서 저장한다")
	void masksResearchOfStoredRawQuestion() {
		AnswerAttemptsHistory source = new AnswerAttemptsHistory(1L, RAW, 1, KEY, LocalDateTime.now(), "", "");
		ReflectionTestUtils.setField(source, "id", 5L);
		source.succeed();

		assertThat(AnswerAttemptsHistory.createResearchAttempt(
				source, Intent.GENERAL, KEY, LocalDateTime.now(), "", "").getQuestion()).isEqualTo(MASKED);
	}
}
