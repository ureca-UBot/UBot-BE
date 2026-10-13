package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.*;
import com.ubot.faq.config.*;
import com.ubot.faq.dto.response.*;
import com.ubot.faq.enums.Intent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FaqSemanticCacheServiceTest {
    private final FaqCacheProperties properties = new FaqCacheProperties();
    private final FaqSemanticCacheService service = new FaqSemanticCacheService(properties);

    @BeforeEach void enableOfficialAnswerStore() { properties.setEnabled(true); }

    @Test void semanticCacheDefaultsToDisabled() {
        assertThat(properties.isSemanticEnabled()).isFalse();
        assertThat(new FaqCacheProperties().isEnabled()).isFalse();
        assertThat(service.shouldSearch("eSIM이 무엇인가요?", false)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,true"})
    void bothSwitchesMustBeEnabledForSearchAndSelection(boolean enabled, boolean semanticEnabled, boolean accepted) {
        properties.setEnabled(enabled);
        properties.setSemanticEnabled(semanticEnabled);
        assertThat(service.shouldSearch("eSIM이 무엇인가요?", false)).isEqualTo(accepted);
        assertThat(service.select(1L, List.of(candidate(.98, true), candidate(.70, false))).isPresent())
                .isEqualTo(accepted);
    }

    @Test void rejectsKnownPartialAnswerCasesButAllowsSingleDualSimIntent() {
        properties.setSemanticEnabled(true);
        for (String question : List.of(
                "esim이 어떤 건지 설명해줘. 그리고 발급 가격도 알려줘.",
                "eSIM이랑 유심을 함께 쓸 수 있나요? 그리고 해외 로밍 신청은 어디서 하나요?",
                "그거 신청은 어떻게 해요?", "제 eSIM 발급 확인해줘")) {
            assertThat(service.shouldSearch(question,false)).as(question).isFalse();
        }
        assertThat(service.shouldSearch("안녕하세요! eSIM이랑 유심을 함께 쓸 수 있나요?",false)).isTrue();
        assertThat(service.shouldSearch("esim이 뭐야!",false)).isTrue();
        assertThat(service.shouldSearch("esim이 뭐야!",true)).isFalse();
        properties.setEnabled(false);
        assertThat(service.shouldSearch("esim이 뭐야!",false)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "모바일 데이터를 추가로 충전하려면 어떤 메뉴에서 신청하는지 알려 주세요.",
            "청구서에 표시된 부가세는 안내 요금에 추가로 붙는 금액인지 궁금합니다.",
            "기본 데이터와 추가로 충전한 데이터 중 어느 쪽부터 사용되는지 궁금합니다.",
            "온라인이 아니라 통신 업체 매장에서도 휴대전화 구매가 가능한지 궁금합니다.",
            "실물 심과 같이 쓰는 게 아니라 쓰던 유심을 이심으로 전환할 수 있나요?"
    })
    void singleIntentQuestionsWithAdditionalOrContrastWordsCanSearch(String question) {
        properties.setSemanticEnabled(true);
        assertThat(service.shouldSearch(question, false)).isTrue();
    }

    @Test void unapprovedCompetitorOrSmallGapBlocksCache() {
        properties.setSemanticEnabled(true);
        assertThat(service.select(1L,List.of(candidate(.98,false),candidate(.7,true)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.98,true),candidate(.9,false)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.94,true),candidate(.7,false)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(Double.NaN,true),candidate(.7,false)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.98,true)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.98,true),candidate(.7,false)))).isPresent();
    }

    @Test void repeatedPunctuationIsAllowedButSeparateRequestsRemainExcluded() {
        properties.setSemanticEnabled(true);
        assertThat(service.shouldSearch("  esim이  뭐야？？？  ",false)).isTrue();
        assertThat(service.shouldSearch("esim이 뭐야!!!",false)).isTrue();
        assertThat(service.shouldSearch("\u00A0\u3000",false)).isFalse();
        for (String question : List.of("esim이 뭐야?? 발급 방법은??", "esim이 뭐야??\n발급 방법도 알려줘",
                "esim이 뭐야?? 그리고 발급 방법도 알려줘", "esim이 뭐야?!?", "제 eSIM 발급\u3000해주세요")) {
            assertThat(service.shouldSearch(question,false)).as(question).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {32L, 48L, 136L, 142L, 100001L})
    void anyApprovedFaqCanPassWithoutAnIdAllowlist(long faqId) {
        properties.setSemanticEnabled(true);
        SemanticFaqCandidateResponseDto approved = new SemanticFaqCandidateResponseDto(
                new FaqSearchResponseDto(faqId,"관리자 등록 질문","현재 공식 답변",
                        .98,Intent.GENERAL,true,2),"등록 표현");
        assertThat(service.select(1L,List.of(approved,candidate(.70,false))))
                .contains(approved.faq());
    }

    @Test void generalQuestionsOutsideTheFormerPilotAreSearchable() {
        properties.setSemanticEnabled(true);
        for (String question : List.of("해외에서 와이파이만 사용하고 싶어요.",
                "해지하면 남은 단말기 할부금은 어떻게 되나요?",
                "번호이동을 하면 기존 통신사는 따로 해지해야 하나요?",
                "번호이동하면 기존 통신사 부가서비스는 어떻게 되나요?")) {
            assertThat(service.shouldSearch(question,false)).as(question).isTrue();
        }
    }

    @Test void approvalAndGeneralIntentAreRequired() {
        properties.setSemanticEnabled(true);
        for (SemanticFaqCandidateResponseDto first : List.of(
                new SemanticFaqCandidateResponseDto(new FaqSearchResponseDto(48L,"질문","답변",.99,Intent.GENERAL,false,1),"표현"),
                new SemanticFaqCandidateResponseDto(new FaqSearchResponseDto(48L,"질문","답변",.99,Intent.USER_DATA,true,1),"표현"),
                new SemanticFaqCandidateResponseDto(new FaqSearchResponseDto(48L,"질문","답변",.99,Intent.STORE_DATA,true,1),"표현"))) {
            assertThat(service.select(1L,List.of(first,candidate(.70,false)))).isEmpty();
        }
    }

    private SemanticFaqCandidateResponseDto candidate(double score, boolean approved) {
        return new SemanticFaqCandidateResponseDto(new FaqSearchResponseDto(approved?14L:15L,"공식 질문","공식 답변",
                score,Intent.GENERAL,approved,1),"비교 표현");
    }
}
