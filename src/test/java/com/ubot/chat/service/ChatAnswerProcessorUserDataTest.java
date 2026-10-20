package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.pgvector.PGvector;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.context.GeneralIntentHandler;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.DenseFaqCandidates;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.retrieval.FaqSentenceFilterService.MatchedFaq;
import com.ubot.faq.retrieval.RetrievalOutcome;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredSource;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.enums.UserRole;
import com.ubot.user.service.UserService;

@DisplayName("사용자 정보 조회 FAQ의 문장 필터 분리")
class ChatAnswerProcessorUserDataTest {
    private static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});
    private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 9, 10, 12, 0);

    private final FaqRetrievalService retrieval = mock(FaqRetrievalService.class);
    private final FaqSentenceFilterService sentenceFilter = mock(FaqSentenceFilterService.class);
    private final UserService users = mock(UserService.class);
    private final NearbyStoreSearcher stores = mock(NearbyStoreSearcher.class);
    private final AiService ai = mock(AiService.class);
    private final List<UnansweredCandidate> unanswered = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"내 이름이 뭐야?", "내 가입일이 언제야?"})
    @DisplayName("사용자 조회 FAQ는 안내 문장을 비교하지 않고 로그인 회원 정보를 수집한다")
    void userFaqCollectsActualUserDataWithoutSentenceFiltering(String question) {
        FaqSearchResponseDto userFaq = faq(1001L, Intent.USER_DATA, 1.0);
        search(question, List.of(userFaq));
        existingUser();
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("회원 정보 답변", null, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(question, 1L), null, () -> { }, unanswered);

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().answer()).isEqualTo("회원 정보 답변");
        assertThat(result.faqs()).containsExactly(userFaq);
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.question()).isEqualTo(question);
        assertThat(materials.faqs()).isEmpty();
        assertThat(materials.sections()).singleElement().satisfies(section -> {
            assertThat(section.content()).contains("이름: 테스트회원", "가입일: 2026-09-10");
            assertThat(section.content()).doesNotContain(userFaq.answer());
        });
        verify(users).getActiveUser(1L);
        verifyNoInteractions(sentenceFilter, stores);
        assertThat(unanswered).isEmpty();
    }

    @Test
    @DisplayName("일반 FAQ 문장이 모두 탈락해도 사용자 정보 조회는 진행한다")
    void userLookupContinuesWhenGeneralSentencesAreRejected() {
        String question = "이름과 가입 방법 알려줘";
        FaqSearchResponseDto generalFaq = faq(1L, Intent.GENERAL, 0.95);
        FaqSearchResponseDto userFaq = faq(1001L, Intent.USER_DATA, 0.9);
        search(question, List.of(generalFaq, userFaq));
        existingUser();
        when(sentenceFilter.filter(QUERY_VECTOR, List.of(generalFaq))).thenReturn(List.of());
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("회원 정보 답변", null, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(question, 1L), null, () -> { }, unanswered);

        assertThat(result.faqs()).containsExactly(userFaq);
        assertThat(capturedMaterials().faqs()).isEmpty();
        verify(sentenceFilter).filter(QUERY_VECTOR, List.of(generalFaq));
        verify(users).getActiveUser(1L);
        assertThat(unanswered).isEmpty();
    }

    @Test
    @DisplayName("혼합 후보는 원래 순서를 유지하고 일반 FAQ의 통과 문장만 자료에 포함한다")
    void mixedCandidatesPreserveOrderAndFilteredGeneralEvidence() {
        String question = "가입 방법과 내 이름을 알려줘";
        FaqSearchResponseDto first = faq(1L, Intent.GENERAL, 0.95);
        FaqSearchResponseDto userFaq = faq(1001L, Intent.USER_DATA, 0.9);
        FaqSearchResponseDto last = faq(2L, Intent.GENERAL, 0.8);
        search(question, List.of(first, userFaq, last));
        existingUser();
        when(sentenceFilter.filter(QUERY_VECTOR, List.of(first, last)))
                .thenReturn(List.of(new MatchedFaq(first, "첫 통과 문장."), new MatchedFaq(last, "마지막 통과 문장.")));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("혼합 답변", null, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(question, 1L), null, () -> { }, unanswered);

        assertThat(result.faqs()).containsExactly(first, userFaq, last);
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L);
        assertThat(materials.faqs()).extracting(FaqSearchResponseDto::answer)
                .containsExactly("첫 통과 문장.", "마지막 통과 문장.");
        assertThat(materials.sections()).hasSize(1);
        verify(sentenceFilter).filter(QUERY_VECTOR, List.of(first, last));
        verify(users).getActiveUser(1L);
    }

    @Test
    @DisplayName("사용자 조회 FAQ도 질문 유사도 기준을 충족하지 못하면 조회하지 않는다")
    void lowQuestionSimilarityDoesNotTriggerUserLookup() {
        String question = "내 이름이 뭐야?";
        FaqSearchResponseDto userFaq = faq(1001L, Intent.USER_DATA, 0.74);
        search(question, List.of(userFaq));

        ChatAnswerResult result = processor().generateAnswer(attempt(question, 1L), null, () -> { }, unanswered);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verifyNoInteractions(users, stores, sentenceFilter, ai);
        assertThat(unanswered).contains(new UnansweredCandidate(
				1, question, question, UnansweredReason.INSUFFICIENT_FAQ, 1001L, 0.74, UnansweredSource.RETRIEVAL));
    }

    @Test
    @DisplayName("비회원은 사용자 조회 없이 정보 없음 안내를 유지하고 FAQ 부족으로 등록하지 않는다")
    void anonymousRequestDoesNotReadUserData() {
        String question = "내 이름이 뭐야?";
        FaqSearchResponseDto userFaq = faq(1001L, Intent.USER_DATA, 1.0);
        search(question, List.of(userFaq));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("사용자 정보를 확인할 수 없습니다.", null, false, "ABSTAIN"));

        ChatAnswerResult result = processor().generateAnswer(attempt(question, null), null, () -> { }, unanswered);

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().abstained()).isTrue();
        assertThat(capturedMaterials().sections()).singleElement()
                .satisfies(section -> assertThat(section.content()).isEqualTo("사용자 정보를 찾을 수 없습니다."));
        verifyNoInteractions(users, stores, sentenceFilter);
        assertThat(unanswered).isEmpty();
    }

    private void search(String question, List<FaqSearchResponseDto> candidates) {
        DenseFaqCandidates dense = new DenseFaqCandidates(candidates, 5, QUERY_VECTOR);
        when(retrieval.searchCandidates(question, null, 3, 0)).thenReturn(dense);
        when(retrieval.rankCandidates(question, dense, 3))
                .thenReturn(new RetrievalOutcome(candidates, candidates.getFirst(), false, null));
    }

    private void existingUser() {
        when(users.getActiveUser(1L)).thenReturn(new UserResponseDto(
                1L, "test@example.com", "테스트회원", null, null, "서울", UserRole.USER, JOINED_AT));
    }

    private ChatAnswerProcessor processor() {
        ChatContextCollector collector = new ChatContextCollector(List.of(
                new GeneralIntentHandler(), new UserIntentHandler(users), new StoreIntentHandler(stores)));
        ChatAnswerProcessor processor = ChatTestFixtures.processor(retrieval, ai, collector, ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache(), sentenceFilter, ChatTestFixtures.inputGuard());
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private AnswerMaterials capturedMaterials() {
        ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.captor();
        verify(ai).generateAnswer(materials.capture());
        return materials.getValue();
    }

    private AnswerAttemptsHistory attempt(String question, Long userId) {
        return AnswerAttemptsHistory.builder().id(100L).userId(userId).question(question).attemptCount(1)
                .createdAt(JOINED_AT).status("PENDING").build();
    }

    private FaqSearchResponseDto faq(Long id, Intent intent, double score) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "조회 안내 " + id, score,
                intent, id + 100, "유사 질문 " + id, false);
    }
}
