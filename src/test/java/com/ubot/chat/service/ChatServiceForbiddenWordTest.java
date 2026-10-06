package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("채팅 금지어 차단 테스트")
class ChatServiceForbiddenWordTest {
	private final FaqVectorService vector = mock(FaqVectorService.class);
	private final AiService ai = mock(AiService.class);
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final ForbiddenWordFilterService filter = mock(ForbiddenWordFilterService.class);
	private final Deque<Runnable> jobs = new ArrayDeque<>();
	private final ChatService service = new ChatService(vector, ai, attempts, filter, mock(UnansweredQuestionService.class), jobs::add);

	@Test
	@DisplayName("금지어가 포함된 질문은 기록·임베딩·검색·LLM 호출 없이 차단한다")
	void blocksBeforeAnyProcessing() {
		doThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED))
				.when(filter).validateForbiddenWord("너 바보야");

		assertThatThrownBy(() -> service.createChat(1L, "너 바보야", null))
				.isInstanceOfSatisfying(ForbiddenWordException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED));

		verifyNoInteractions(attempts, vector, ai);
		assertThat(jobs).isEmpty();
	}

	@Test
	@DisplayName("금지어가 없으면 필터를 통과해 답변 생성을 시작한다")
	void startsGenerationWhenPassed() {
		AnswerAttemptsHistory attempt = mock(AnswerAttemptsHistory.class);
		when(attempts.createAnswerAttempt(1L, "요금제 알려줘")).thenReturn(attempt);

		service.createChat(1L, "요금제 알려줘", null);

		verify(filter).validateForbiddenWord("요금제 알려줘");
		verify(attempts).createAnswerAttempt(1L, "요금제 알려줘");
	}

	@Test
	@DisplayName("빈 질문은 금지어 검사 전에 기본 검증에서 거절한다")
	void blankQuestionFailsBasicValidationFirst() {
		assertThatThrownBy(() -> service.createChat(1L, " ", null)).isInstanceOf(com.ubot.chat.exception.ChatException.class);

		verifyNoInteractions(filter);
	}
}
