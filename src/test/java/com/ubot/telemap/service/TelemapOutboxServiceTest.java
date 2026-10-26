package com.ubot.telemap.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("통신 지도 Outbox 발행 서비스")
class TelemapOutboxServiceTest {

    @Test
    @DisplayName("저장된 질문 로그마다 고유한 급증 질문 이벤트를 발행한다")
    void enqueuesEachQuestionLog() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        QuestionLog first = mock(QuestionLog.class);
        QuestionLog second = mock(QuestionLog.class);
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);

        new TelemapOutboxService(repository).enqueueSurgingQuestions(List.of(first, second));

        InOrder order = inOrder(repository);
        order.verify(repository).enqueue(OutboxEventType.SURGING_QUESTION, "1", Map.of("questionLogId", 1L));
        order.verify(repository).enqueue(OutboxEventType.SURGING_QUESTION, "2", Map.of("questionLogId", 2L));
    }
}
