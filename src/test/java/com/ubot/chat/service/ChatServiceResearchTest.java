package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("의도 재검색 서비스 테스트")
class ChatServiceResearchTest {
    private static final String KEY = "a".repeat(64);

    private final FaqVectorService vector = ChatTestFixtures.vectorService();
    private final AiService ai = mock(AiService.class);
    private final ChatAttemptsService attempts = mock(ChatAttemptsService.class);
    private final IpRegionResolver ipRegionResolver = mock(IpRegionResolver.class);
    private final ForbiddenWordFilterService filter = mock(ForbiddenWordFilterService.class);
    private final ChatJobTestHarness harness = new ChatJobTestHarness(attempts, new ChatAnswerProcessor(
            FaqRetrievalService.denseOnly(vector), ai, mock(UnansweredQuestionService.class), ChatTestFixtures.collector(),
            ChatTestFixtures.disabledCache(), ChatTestFixtures.passthroughSentenceFilter()));
    private final ChatService service = new ChatService(attempts, filter, harness.submissionService,
            ipRegionResolver);

    @Test
    @DisplayName("재검색 attempt를 만들어 작업으로 접수한다")
    void createsResearchAttemptAndQueuesJob() {
        AnswerAttemptsHistory attempt = mock(AnswerAttemptsHistory.class);
        when(attempt.getId()).thenReturn(9L);
        when(attempts.createResearchAttempt(1L, KEY, Intent.STORE_DATA)).thenReturn(attempt);

        service.researchChat(1L, KEY, Intent.STORE_DATA, null, null);

        verify(attempts).createResearchAttempt(1L, KEY, Intent.STORE_DATA);
        assertThat(harness.jobs).singleElement().satisfies(job -> {
            assertThat(job.getAttemptId()).isEqualTo(9L);
            assertThat(job.getStatus()).isEqualTo(ChatJobStatus.QUEUED);
        });
        verifyNoInteractions(vector, ai);
    }

    @Test
    @DisplayName("질문은 원본에서 가져오므로 금지어 필터를 다시 호출하지 않는다")
    void doesNotRevalidateQuestion() {
        when(attempts.createResearchAttempt(1L, KEY, Intent.GENERAL)).thenReturn(mock(AnswerAttemptsHistory.class));

        service.researchChat(1L, KEY, Intent.GENERAL, null, null);

        verifyNoInteractions(filter);
    }

    @Test
    @DisplayName("멱등키 형식이 틀리면 DB 접근 없이 CHAT-001로 거절한다")
    void invalidKeyIsRejectedBeforeAnyAccess() {
        assertThatThrownBy(() -> service.researchChat(1L, "not-a-key", Intent.GENERAL, null, null))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.INVALID_CHAT_REQUEST));
        assertThatThrownBy(() -> service.researchChat(1L, null, Intent.GENERAL, null, null))
                .isInstanceOf(ChatException.class);

        verifyNoInteractions(attempts);
        assertThat(harness.jobs).isEmpty();
    }

    @Test
    @DisplayName("의도가 없으면 DB 접근 없이 CHAT-001로 거절한다")
    void nullIntentIsRejected() {
        assertThatThrownBy(() -> service.researchChat(1L, KEY, null, null, null))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.INVALID_CHAT_REQUEST));

        verifyNoInteractions(attempts);
    }

    @Test
    @DisplayName("재검색 attempt 생성이 거절되면 작업을 접수하지 않는다")
    void doesNotQueueWhenAttemptCreationIsRejected() {
        when(attempts.createResearchAttempt(1L, KEY, Intent.USER_DATA))
                .thenThrow(new ChatException(ChatErrorCode.ALREADY_RESEARCHED));

        assertThatThrownBy(() -> service.researchChat(1L, KEY, Intent.USER_DATA, null, null))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.ALREADY_RESEARCHED));

        assertThat(harness.jobs).isEmpty();
    }
}
