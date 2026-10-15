package com.ubot.faq.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ubot.faq.cache.FaqCacheDecision.Reason;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

@DisplayName("공식 FAQ 캐시 판정")
class FaqSemanticCacheServiceTest {
    private final FaqCacheProperties properties = new FaqCacheProperties();
    private final FaqVectorRepository repository = mock(FaqVectorRepository.class);
    private final FaqSemanticCacheService service = new FaqSemanticCacheService(properties, repository);

    @BeforeEach
    void enableCache() {
        properties.setEnabled(true);
    }

    @Test
    @DisplayName("OFF이면 검증 조회 없이 기존 경로를 사용한다")
    void disabledDoesNotReadDatabase() {
        properties.setEnabled(false);
        assertThat(service.minimumCandidateCount(null)).isZero();
        assertThat(service.evaluate(null, candidates(0.99, 0.5)).reason()).isEqualTo(Reason.DISABLED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("missCases")
    @DisplayName("점수·승인·의도·후보 계약을 통과하지 못하면 공식 답변을 조회하지 않는다")
    void rejectsWithoutApprovingAnotherFaq(Intent requestedIntent, List<FaqSearchResponseDto> candidates, Reason reason) {
        FaqCacheDecision decision = service.evaluate(requestedIntent, candidates);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.faq()).isEmpty();
        verifyNoInteractions(repository);
    }

    private static Stream<Arguments> missCases() {
        return Stream.of(
                Arguments.of(Intent.USER_DATA, candidates(0.99, 0.5), Reason.CONTEXT_REQUIRED),
                Arguments.of(Intent.STORE_DATA, candidates(0.99, 0.5), Reason.CONTEXT_REQUIRED),
                Arguments.of(null, List.of(), Reason.NO_CANDIDATES),
                Arguments.of(null, null, Reason.NO_CANDIDATES),
                Arguments.of(null, candidates(0.949, 0.5), Reason.BELOW_SIMILARITY),
                Arguments.of(null, candidates(0.99, 0.90), Reason.BELOW_MARGIN),
                Arguments.of(null, candidates(0.99, 0.99), Reason.BELOW_MARGIN),
                Arguments.of(null, List.of(faq(1, 0.99, true, Intent.GENERAL)), Reason.INSUFFICIENT_CANDIDATES),
                Arguments.of(null, List.of(faq(1, 0.99, false, Intent.GENERAL), faq(2, 0.8, true, Intent.GENERAL)),
                        Reason.NOT_CACHEABLE_GENERAL),
                Arguments.of(null, List.of(faq(1, 0.99, true, Intent.USER_DATA), faq(2, 0.5, true, Intent.GENERAL)),
                        Reason.NOT_CACHEABLE_GENERAL),
                Arguments.of(null, List.of(faq(1, 0.99, true, Intent.STORE_DATA), faq(2, 0.5, true, Intent.GENERAL)),
                        Reason.NOT_CACHEABLE_GENERAL),
                Arguments.of(null, candidates(Double.NaN, 0.5), Reason.INVALID_CANDIDATES),
                Arguments.of(null, candidates(1.1, 0.5), Reason.INVALID_CANDIDATES),
                Arguments.of(null, candidates(0.99, -1.1), Reason.INVALID_CANDIDATES),
                Arguments.of(null, candidates(0.99, Double.POSITIVE_INFINITY), Reason.INVALID_CANDIDATES),
                Arguments.of(null, candidates(0.5, 0.99), Reason.INVALID_CANDIDATES),
                Arguments.of(null, List.of(faq(1, 0.99, true, Intent.GENERAL), faq(1, 0.5, true, Intent.GENERAL)),
                        Reason.INVALID_CANDIDATES),
                Arguments.of(null, List.of(new FaqSearchResponseDto(1L, "질문", "답변", 0.99, Intent.GENERAL,
                                null, "표현", true, 1), faq(2, 0.5, false, Intent.GENERAL)), Reason.FAQ_CHANGED));
    }

    @Test
    @DisplayName("서로 다른 FAQ의 차이를 계산하고 현재 DB의 공식 답변을 수정 없이 사용한다")
    void returnsCurrentOfficialAnswerVerbatim() {
        String officialAnswer = "공식 답변입니다.\n줄바꿈과 공백도 유지합니다. ";
        when(repository.findCurrentCacheableAnswer(1L, 101L, 3)).thenReturn(Optional.of(officialAnswer));

        FaqCacheDecision decision = service.evaluate(null, candidates(0.98, 0.7));

        assertThat(decision.reason()).isEqualTo(Reason.HIT);
        assertThat(decision.faq()).get().satisfies(faq -> {
            assertThat(faq.answer()).isEqualTo(officialAnswer);
            assertThat(faq.faqId()).isEqualTo(1L);
            assertThat(faq.variantId()).isEqualTo(101L);
        });
        assertThat(decision.similarity()).isEqualTo(0.98);
        assertThat(decision.margin()).isCloseTo(0.28, org.assertj.core.data.Offset.offset(1e-12));
        verify(repository).findCurrentCacheableAnswer(1L, 101L, 3);
    }

    @Test
    @DisplayName("유사도와 차이가 기준과 같으면 통과한다")
    void inclusiveThresholds() {
        properties.setMinSimilarity(0.95);
        properties.setMinMargin(0.25);
        when(repository.findCurrentCacheableAnswer(1L, 101L, 3)).thenReturn(Optional.of("공식 답변"));
        assertThat(service.evaluate(Intent.GENERAL, candidates(1.0, 0.75)).reason()).isEqualTo(Reason.HIT);
        properties.setMinMargin(0.2);
        assertThat(service.evaluate(null, candidates(0.95, 0.5)).reason()).isEqualTo(Reason.HIT);
    }

    @Test
    @DisplayName("FAQ가 변경되었거나 답변이 비어 있으면 승인하지 않는다")
    void changedOrEmptyAnswerFallsBack() {
        when(repository.findCurrentCacheableAnswer(1L, 101L, 3))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(" \n"));
        assertThat(service.evaluate(null, candidates(0.99, 0.5)).reason()).isEqualTo(Reason.FAQ_CHANGED);
        assertThat(service.evaluate(null, candidates(0.99, 0.5)).reason()).isEqualTo(Reason.FAQ_CHANGED);
    }

    @Test
    @DisplayName("추가 검증 DB 조회가 실패하면 기존 검색 결과로 진행하도록 MISS를 반환한다")
    void verificationFailureFallsBack() {
        when(repository.findCurrentCacheableAnswer(1L, 101L, 3))
                .thenThrow(new DataAccessResourceFailureException("검증 조회 실패"));
        assertThat(service.evaluate(null, candidates(0.99, 0.5)).reason()).isEqualTo(Reason.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("캐시 사용 시 일반 검색은 두 후보를 요구하고 개인정보·매장 재검색은 요구하지 않는다")
    void requiresRunnerUpOnlyForEligibleSearch() {
        assertThat(service.minimumCandidateCount(null)).isEqualTo(2);
        assertThat(service.minimumCandidateCount(Intent.GENERAL)).isEqualTo(2);
        assertThat(service.minimumCandidateCount(Intent.USER_DATA)).isZero();
        assertThat(service.minimumCandidateCount(Intent.STORE_DATA)).isZero();
    }

    private static List<FaqSearchResponseDto> candidates(double first, double second) {
        return List.of(faq(1, first, true, Intent.GENERAL), faq(2, second, false, Intent.GENERAL));
    }

    private static FaqSearchResponseDto faq(long id, double score, boolean cacheable, Intent intent) {
        return new FaqSearchResponseDto(id, "질문 " + id, "검색 당시 답변", score, intent,
                id + 100, "유사표현 " + id, cacheable, 3);
    }
}
