package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.QuestionEmbedding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** JSON으로 전달되는 기존 Outbox 경계만 확인합니다. 실제 DB·임베딩은 호출하지 않습니다. */
class UnansweredOutboxHandlerTest {
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final UnansweredQuestionService writer = mock(UnansweredQuestionService.class);
    private final UnansweredQuestionService gateway = new UnansweredQuestionService(
            embedding, mock(EmbeddingProfileService.class), mock(UnansweredQuestionRepository.class),
            mock(UnansweredQuestionVectorRepository.class), mock(RejectedQuestionDuplicateChecker.class),
            mock(AnswerAttemptsHistoryRepository.class), outbox, .6);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void 출력_후보의_출처와_벡터는_JSON을_거쳐_저장_처리로_전달한다() {
        var candidate = new UnansweredCandidate(2, "회선 정지 절차", "회선 정지는 어떻게 하나요?",
                UnansweredReason.INSUFFICIENT_FAQ, 17L, .91, UnansweredSource.OUTPUT_GUARD,
                3L, List.of(1F, 0F), null);
        gateway.enqueueUnansweredQuestion(71L, candidate);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq(OutboxEventType.UNANSWERED_QUESTION), eq("71:2"), payload.capture());
        new UnansweredOutboxHandler(writer).handle(json.readTree(json.writeValueAsString(payload.getValue())));

        var vector = ArgumentCaptor.forClass(QuestionEmbedding.class);
        verify(writer).createUnansweredQuestion(eq(71L), eq(2), eq(candidate.subQuestion()), eq(candidate.userText()),
                eq(candidate.reason()), eq(17L), eq(.91), eq(UnansweredSource.OUTPUT_GUARD),
                vector.capture(), eq(OptionalDouble.empty()));
        assertThat(vector.getValue().question()).isEqualTo(candidate.subQuestion());
        assertThat(vector.getValue().profileId()).isEqualTo(3L);
        assertThat(vector.getValue().vector().toArray()).containsExactly(1F, 0F);
        verifyNoInteractions(embedding);
    }

    @Test
    void 이전_형식의_Outbox도_출처_기본값과_벡터_없음으로_처리한다() {
        var payload = json.readTree("""
                {"attemptId":71,"subQuestionIndex":1,"subQuestion":"일반 문의","userText":"일반 문의",
                 "reason":"NO_FAQ","bestFaqId":null,"bestSimilarity":null}
                """);
        new UnansweredOutboxHandler(writer).handle(payload);
        verify(writer).createUnansweredQuestion(71L, 1, "일반 문의", "일반 문의", UnansweredReason.NO_FAQ,
                null, null, UnansweredSource.RETRIEVAL, null, OptionalDouble.empty());
    }

    @Test
    void 검색_미응답도_같은_대기열과_기존_중복키를_사용한다() {
        gateway.enqueueUnansweredQuestion(71L, new UnansweredCandidate(1, "일반 문의", "일반 문의",
                UnansweredReason.NO_FAQ, null, null, UnansweredSource.RETRIEVAL));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq(OutboxEventType.UNANSWERED_QUESTION), eq("71:1"), payload.capture());
        var node = json.readTree(json.writeValueAsString(payload.getValue()));
        assertThat(node.get("source").asString()).isEqualTo("RETRIEVAL");
        assertThat(node.has("queryEmbedding")).isFalse();
        assertThat(node.has("rejectedThreshold")).isFalse();
        verifyNoInteractions(embedding, writer);
    }

    @Test
    void 확정해서_넘긴_반려_기준만_저장_처리로_전달한다() {
        var payload = json.readTree("""
                {"attemptId":71,"subQuestionIndex":1,"subQuestion":"일반 문의","userText":"일반 문의",
                 "reason":"INSUFFICIENT_FAQ","bestFaqId":null,"bestSimilarity":null,
                 "source":"OUTPUT_GUARD","rejectedThreshold":0.97}
                """);
        new UnansweredOutboxHandler(writer).handle(payload);
        verify(writer).createUnansweredQuestion(71L, 1, "일반 문의", "일반 문의", UnansweredReason.INSUFFICIENT_FAQ,
                null, null, UnansweredSource.OUTPUT_GUARD, null, OptionalDouble.of(.97));
    }

    @Test
    void 후보_벡터는_생성_후_원래_목록이_바뀌어도_유지한다() {
        var vector = new ArrayList<>(List.of(1F, 0F));
        var candidate = new UnansweredCandidate(1, "질문", "질문", UnansweredReason.NO_FAQ, null, null,
                UnansweredSource.RETRIEVAL, 3L, vector, null);
        vector.set(0, 9F);
        assertThat(candidate.queryEmbedding()).containsExactly(1F, 0F);
    }
}
