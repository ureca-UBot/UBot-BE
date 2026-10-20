package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ubot.ai.service.GuardPipelineFixtures.*;
import com.pgvector.PGvector;
import com.ubot.chat.service.QuestionRequirementMatcher;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository.RejectedGroup;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class RejectedQuestionDuplicateCheckerTest {
    private final QuestionRequirementMatcher matcher = mock(QuestionRequirementMatcher.class);
    private final UnansweredQuestionVectorRepository repository = mock(UnansweredQuestionVectorRepository.class);
    private final RejectedQuestionDuplicateChecker checker = new RejectedQuestionDuplicateChecker(repository, matcher);
    private final PGvector vector = vector();
    private final List<String> same = List.of("대상:회선", "요구:정지방법");
    @BeforeEach void setUp() { when(matcher.signature(QUESTION)).thenReturn(Optional.of(same)); }
    private Decision check(QuestionEmbedding embedding) { return checker.check(QUESTION, 1L, embedding, .97); }
    private QuestionEmbedding reusable() { return new QuestionEmbedding(QUESTION, 1L, vector); }
    @Test void 유사도와_요구가_모두_같을_때만_재등록_생략이다() {
        when(repository.findRejectedGroups(vector, 1L, .97, 20)).thenReturn(List.of(new RejectedGroup(7L, QUESTION, .99)));
        assertThat(check(reusable()).skipRegistration()).isTrue();
        verify(repository).findRejectedGroups(vector, 1L, .97, 20);
        verify(repository, never()).findNearestGroupId(any(), any(), anyDouble());
        verify(repository, never()).saveGroup(any(), any(), any(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"대상차이", "조건차이", "요구차이", "부정차이"})
    void 매우_비슷해도_요구_구조가_다르면_후보를_유지한다(String question) {
        when(matcher.signature(question)).thenReturn(Optional.of(List.of(question)));
        when(repository.findRejectedGroups(vector, 1L, .97, 20)).thenReturn(List.of(new RejectedGroup(7L, question, .999)));
        assertThat(check(reusable()).skipRegistration()).isFalse();
    }
    @Test void 가장_가까운_묶음이_달라도_뒤의_같은_요구를_확인한다() {
        when(matcher.signature("다른 대상")).thenReturn(Optional.of(List.of("다름")));
        when(repository.findRejectedGroups(vector, 1L, .97, 20)).thenReturn(List.of(
                new RejectedGroup(7L, "다른 대상", .999), new RejectedGroup(8L, QUESTION, .98)));
        assertThat(check(reusable()).rejectedGroupId()).isEqualTo(8L);
    }
    @Test void 기준_미달과_수치이상_점수는_생략하지_않는다() {
        when(repository.findRejectedGroups(vector, 1L, .97, 20)).thenReturn(List.of(
                new RejectedGroup(7L, QUESTION, .96), new RejectedGroup(8L, QUESTION, Double.NaN)));
        assertThat(check(reusable()).skipRegistration()).isFalse();
    }
    @Test void 다른_프로필의_벡터는_빌리지_않고_같은_프로필을_찾는다() {
        when(repository.findQuestionEmbedding(QUESTION, 1L)).thenReturn(Optional.of(vector));
        check(new QuestionEmbedding(QUESTION, 2L, vector));
        verify(repository).findQuestionEmbedding(QUESTION, 1L);
        verify(repository).findRejectedGroups(vector, 1L, .97, 20);
    }
    @Test void 다른_문장_벡터는_재사용하지_않는다() {
        assertThat(check(new QuestionEmbedding("다른 질문", 1L, vector)).reason()).isEqualTo(Reason.VECTOR_REQUIRED);
        verify(repository).findQuestionEmbedding(QUESTION, 1L);
        verify(repository, never()).findRejectedGroups(any(), any(), anyDouble(), anyInt());
    }
    @Test void 벡터가_없거나_조회가_실패해도_후보는_유지한다() {
        assertThat(check(null).skipRegistration()).isFalse();
        when(repository.findQuestionEmbedding(QUESTION, 1L)).thenThrow(new DataAccessResourceFailureException("mock failure"));
        assertThat(check(null).reason()).isEqualTo(Reason.CHECK_UNAVAILABLE);
    }
    @Test void 요구가_모호하면_DB조회조차_하지_않고_유지한다() {
        when(matcher.signature(QUESTION)).thenReturn(Optional.empty());
        assertThat(check(reusable()).skipRegistration()).isFalse();
        verifyNoInteractions(repository);
    }
    @ParameterizedTest @ValueSource(doubles = {-1, 0, 1.1, Double.NaN})
    void 잘못된_테스트_임계값은_거절한다(double threshold) {
        assertThatIllegalArgumentException().isThrownBy(() -> checker.check(QUESTION, 1L, reusable(), threshold));
    }
}
