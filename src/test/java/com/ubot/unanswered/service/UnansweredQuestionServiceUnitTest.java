package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ubot.ai.service.GuardPipelineFixtures.*;
import com.pgvector.PGvector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** PostgreSQL 통합 테스트의 저장 시나리오를 모의 저장소로 확인합니다. SQL·잠금 효과 자체는 검증하지 않습니다. */
class UnansweredQuestionServiceUnitTest {
    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
    private final UnansweredQuestionRepository questions = mock(UnansweredQuestionRepository.class);
    private final UnansweredQuestionVectorRepository vectors = mock(UnansweredQuestionVectorRepository.class);
    private final RejectedQuestionDuplicateChecker rejected = mock(RejectedQuestionDuplicateChecker.class);
    private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
    private final PGvector vector = vector();
    private final List<Stored> stored = new ArrayList<>();
    private final UnansweredQuestionService service = new UnansweredQuestionService(embedding, profiles, questions,
            vectors, rejected, attempts, mock(com.ubot.outbox.OutboxEventRepository.class),
            mock(com.ubot.unanswered.repository.UnansweredReverifyRepository.class), .6, .75);
    private record Stored(Long attemptId, int index, String query, String userText, UnansweredSource source) { }

    @BeforeEach void setUp() {
        var initial = AnswerAttemptsHistory.builder().id(100L).userId(1L).conversationId(4L)
                .question(QUESTION).attemptCount(1).idempotencyKey("same-chain").build();
        when(attempts.findById(anyLong())).thenAnswer(call -> Optional.of(AnswerAttemptsHistory.builder()
                .id(call.getArgument(0)).userId(1L).conversationId(4L).question(QUESTION).attemptCount(1)
                .idempotencyKey("same-chain").build()));
        when(attempts.findInitialAttemptsForLock(1L, "same-chain", 1)).thenReturn(Optional.of(initial));
        when(profiles.getCurrentProfileId()).thenReturn(1L);
        when(vectors.findNearestGroupId(any(), eq(1L), eq(.6))).thenReturn(Optional.of(50L));
        when(embedding.embedText(anyString())).thenReturn(vector);
        when(questions.existsByAttemptIdAndSubQuestionIndex(any(), anyInt())).thenAnswer(call -> stored.stream()
                .anyMatch(s -> s.attemptId().equals(call.getArgument(0)) && s.index() == (int) call.getArgument(1)));
        when(questions.existsInSameQuestion(any(), anyString(), anyString())).thenAnswer(call -> stored.stream()
                .anyMatch(s -> s.query().equals(call.getArgument(1))
                        || !((String) call.getArgument(2)).isEmpty() && s.userText().equals(call.getArgument(2))));
        doAnswer(call -> { stored.add(new Stored(call.getArgument(0), call.getArgument(1), call.getArgument(3),
                call.getArgument(4), call.getArgument(10))); return null; })
                .when(vectors).saveQuestion(any(), anyInt(), any(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
    }
    private void save(Long id, int index, String query, String userText, QuestionEmbedding reusable, OptionalDouble threshold) {
        service.createUnansweredQuestion(id, index, query, userText, UnansweredReason.INSUFFICIENT_FAQ,
                17L, .91, UnansweredSource.OUTPUT_GUARD, reusable, threshold);
    }
    private QuestionEmbedding reusable() { return new QuestionEmbedding(QUESTION, 1L, vector); }

    @Test void 출력_가드_출처와_기존_저장_묶기를_재사용한다() {
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        assertThat(stored).singleElement().extracting(Stored::source).isEqualTo(UnansweredSource.OUTPUT_GUARD);
        verify(vectors).saveQuestion(100L, 1, 50L, QUESTION, QUESTION, vector, 1L,
                UnansweredReason.INSUFFICIENT_FAQ, 17L, .91, UnansweredSource.OUTPUT_GUARD);
        verify(vectors).updateGroupCentroid(50L, 1L);
        verifyNoInteractions(embedding, rejected);
    }
    @Test void 같은_시도와_같은_재시도는_한번만_저장한다() {
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        save(101L, 2, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        assertThat(stored).hasSize(1);
        verify(vectors, times(1)).updateGroupCentroid(50L, 1L);
    }
    @Test void 재시도_검색어와_순번이_바뀌어도_같은_안전한_원문이면_중복이다() {
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        save(101L, 3, "회선 이용 정지 절차가 궁금합니다.", QUESTION, null, OptionalDouble.empty());
        assertThat(stored).hasSize(1);
        verifyNoInteractions(embedding);
    }
    @Test void 원문_조각과_요구가_다르면_다른_하위_질문은_보존한다() {
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        save(101L, 2, "기기 변경 방법", "기기 변경 방법", null, OptionalDouble.empty());
        assertThat(stored).hasSize(2);
    }
    @Test void 미정인_반려_기준은_자동_생략을_실행하지_않는다() {
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        verifyNoInteractions(rejected);
        assertThat(stored).hasSize(1);
    }
    @Test void 테스트용_반려_확정은_관리자_저장만_생략한다() {
        when(rejected.check(eq(QUESTION), eq(1L), any(), eq(.97)))
                .thenReturn(new Decision(true, 33L, Reason.SAME_REJECTED_REQUIREMENT, VectorSource.CURRENT_REQUEST));
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.of(.97));
        assertThat(stored).isEmpty();
        verify(vectors, never()).findNearestGroupId(any(), any(), anyDouble());
        verify(vectors, never()).updateGroupCentroid(any(), any());
    }
    @Test void 테스트용_판정이_모호하면_후보를_그대로_저장한다() {
        when(rejected.check(eq(QUESTION), eq(1L), any(), eq(.97)))
                .thenReturn(new Decision(false, null, Reason.UNDETERMINED, VectorSource.CURRENT_REQUEST));
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.of(.97));
        assertThat(stored).hasSize(1);
    }
    @Test void 다른_문장이나_프로필의_벡터는_재사용하지_않는다() {
        var wrong = new QuestionEmbedding("다른 질문", 2L, vector);
        save(100L, 1, QUESTION, QUESTION, wrong, OptionalDouble.empty());
        verify(vectors).findQuestionEmbedding(QUESTION, 1L);
        verify(embedding).embedText(QUESTION);
    }
    @Test void 저장된_같은_문장과_프로필의_벡터가_있으면_모의_임베딩도_호출하지_않는다() {
        when(vectors.findQuestionEmbedding(QUESTION, 1L)).thenReturn(Optional.of(vector));
        save(100L, 1, QUESTION, QUESTION, null, OptionalDouble.empty());
        verifyNoInteractions(embedding);
        assertThat(stored).hasSize(1);
    }
    @Test void 안전한_원문_조각이_없으면_빈값이고_공격_원문은_조회하지_않는다() {
        save(100L, 1, QUESTION, null, reusable(), OptionalDouble.empty());
        assertThat(stored.getFirst().userText()).isEmpty();
    }
    @Test void 최초_시도_잠금은_중복_확인보다_먼저_요청한다() {
        save(100L, 1, QUESTION, QUESTION, reusable(), OptionalDouble.empty());
        var order = inOrder(attempts, questions, vectors);
        order.verify(attempts).findById(100L);
        order.verify(attempts).findInitialAttemptsForLock(1L, "same-chain", 1);
        order.verify(questions).existsByAttemptIdAndSubQuestionIndex(100L, 1);
        order.verify(questions).existsInSameQuestion(100L, QUESTION, QUESTION);
    }
    @Test void 기존_검색_실패_저장은_RETRIEVAL_출처를_유지한다() {
        service.createUnansweredQuestion(100L, 1, QUESTION, QUESTION, UnansweredReason.NO_FAQ, null, null);
        assertThat(stored.getFirst().source()).isEqualTo(UnansweredSource.RETRIEVAL);
        verifyNoInteractions(rejected);
    }
}
