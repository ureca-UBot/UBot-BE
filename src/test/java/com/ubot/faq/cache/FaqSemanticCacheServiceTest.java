package com.ubot.faq.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ubot.faq.cache.FaqCacheDecision.Reason;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

@DisplayName("공식 FAQ 캐시 판정")
class FaqSemanticCacheServiceTest {
    private final FaqCacheProperties properties = new FaqCacheProperties();
    private final FaqSemanticCacheService service = new FaqSemanticCacheService(properties);

    @BeforeEach
    void enableCache() {
        properties.setEnabled(true);
    }

    @Test
    @DisplayName("OFF이면 기존 경로를 사용한다")
    void disabledDoesNotApprove() {
        properties.setEnabled(false);
        assertThat(service.minimumCandidateCount(null)).isZero();
        FaqCacheDecision decision = service.evaluate(null, candidates(0.99, 0.5));
        assertThat(decision.reason()).isEqualTo(Reason.DISABLED);
        assertThat(decision.faq()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("missCases")
    @DisplayName("점수·승인·의도·후보 계약을 통과하지 못하면 다른 FAQ를 대신 승인하지 않는다")
    void rejectsWithoutApprovingAnotherFaq(Intent requestedIntent, List<FaqSearchResponseDto> candidates, Reason reason) {
        FaqCacheDecision decision = service.evaluate(requestedIntent, candidates);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.faq()).isEmpty();
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
                Arguments.of(null, Arrays.asList(null, faq(2, 0.5, false, Intent.GENERAL)), Reason.INVALID_CANDIDATES),
                Arguments.of(null, List.of(new FaqSearchResponseDto(null, "질문", "답변", 0.99, Intent.GENERAL,
                        101L, "표현", true), faq(2, 0.5, false, Intent.GENERAL)), Reason.INVALID_CANDIDATES));
    }

    @Test
    @DisplayName("검색된 공식 FAQ 객체와 답변을 수정 없이 반환한다")
    void returnsSearchedOfficialAnswerVerbatim() {
        String officialAnswer = "공식 답변입니다.\n줄바꿈과 공백도 유지합니다. ";
        FaqSearchResponseDto best = new FaqSearchResponseDto(1L, "질문 1", officialAnswer, 0.98,
                Intent.GENERAL, 101L, "유사표현 1", true);
        FaqCacheDecision decision = service.evaluate(null, List.of(best, faq(2, 0.7, false, Intent.GENERAL)));

        assertThat(decision.reason()).isEqualTo(Reason.HIT);
        assertThat(decision.faq()).containsSame(best);
        assertThat(decision.faq().orElseThrow().answer()).isEqualTo(officialAnswer);
        assertThat(decision.similarity()).isEqualTo(0.98);
        assertThat(decision.margin()).isCloseTo(0.28, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("캐시 판정에 사용하지 않는 variant 메타데이터가 없어도 변경으로 판단하지 않는다")
    void optionalVariantMetadataDoesNotImplyFaqChange() {
        FaqSearchResponseDto best = new FaqSearchResponseDto(1L, "질문", "검색 당시 답변", 0.99,
                Intent.GENERAL, null, null, true);
        assertThat(service.evaluate(null, List.of(best, faq(2, 0.5, false, Intent.GENERAL))).faq()).containsSame(best);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    @DisplayName("검색된 답변이 비어 있으면 변경이 아닌 잘못된 후보로 분류한다")
    void emptyAnswerIsInvalidCandidate(String answer) {
        FaqSearchResponseDto best = new FaqSearchResponseDto(1L, "질문", answer, 0.99,
                Intent.GENERAL, 101L, "표현", true);
        FaqCacheDecision decision = service.evaluate(null, List.of(best, faq(2, 0.5, false, Intent.GENERAL)));
        assertThat(decision.reason()).isEqualTo(Reason.INVALID_CANDIDATES);
        assertThat(decision.faq()).isEmpty();
    }

    @Test
    @DisplayName("MISS 판정은 원래 후보의 내용과 순서를 변경하지 않는다")
    void missLeavesInputCandidatesUntouched() {
        List<FaqSearchResponseDto> candidates = new ArrayList<>(candidates(0.99, 0.90));
        List<FaqSearchResponseDto> original = List.copyOf(candidates);
        assertThat(service.evaluate(null, candidates).reason()).isEqualTo(Reason.BELOW_MARGIN);
        assertThat(candidates).containsExactlyElementsOf(original);
    }

    @Test
    @DisplayName("유사도와 차이가 기준과 같으면 통과한다")
    void inclusiveThresholds() {
        properties.setMinSimilarity(0.95);
        properties.setMinMargin(0.25);
        assertThat(service.evaluate(Intent.GENERAL, candidates(1.0, 0.75)).reason()).isEqualTo(Reason.HIT);
        properties.setMinMargin(0.2);
        assertThat(service.evaluate(null, candidates(0.95, 0.5)).reason()).isEqualTo(Reason.HIT);
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
                id + 100, "유사표현 " + id, cacheable);
    }
}
