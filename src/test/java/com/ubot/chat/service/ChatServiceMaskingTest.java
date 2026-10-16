package com.ubot.chat.service;

import static org.mockito.Mockito.*;

import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("채팅 질문 개인정보 마스킹 테스트")
class ChatServiceMaskingTest {
	private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
	private final Deque<Runnable> jobs = new ArrayDeque<>();
	private final ChatService service = new ChatService(attempts, mock(ForbiddenWordFilterService.class),
			new ChatAnswerExecutor(
					new ChatAnswerProcessor(FaqRetrievalService.denseOnly(mock(FaqVectorService.class)),
							mock(AiService.class), mock(UnansweredQuestionService.class),
							ChatTestFixtures.collector(), ChatTestFixtures.disabledCache()),
					attempts, jobs::add));

	@Test
	@DisplayName("회원 질문은 마스킹한 뒤 답변 시도로 저장한다")
	void masksMemberQuestion() {
		when(attempts.createAnswerAttempt(1L, 10L, "010-****-5678 번호 해지하고 싶어요"))
				.thenReturn(mock(AnswerAttemptsHistory.class));

		service.createChat(1L, 10L, "01012345678 번호 해지하고 싶어요", null, null);

		verify(attempts).createAnswerAttempt(1L, 10L, "010-****-5678 번호 해지하고 싶어요");
	}

	@Test
	@DisplayName("비회원 질문은 마스킹한 뒤 답변 시도로 저장한다")
	void masksGuestQuestion() {
		when(attempts.createGuestAnswerAttempt(10L, "주민번호 900101-******* 로 가입 가능해요?"))
				.thenReturn(mock(AnswerAttemptsHistory.class));

		service.createGuestChat(10L, "주민번호 900101-1234567 로 가입 가능해요?", null, null);

		verify(attempts).createGuestAnswerAttempt(10L, "주민번호 900101-******* 로 가입 가능해요?");
	}
}
