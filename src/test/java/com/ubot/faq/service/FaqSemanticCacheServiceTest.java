package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.*;
import com.ubot.faq.config.*;
import com.ubot.faq.dto.response.*;
import com.ubot.faq.enums.Intent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class FaqSemanticCacheServiceTest {
    private final FaqSemanticCacheProperties properties = new FaqSemanticCacheProperties();
    private final FaqCacheProperties cache = new FaqCacheProperties();
    private final FaqSemanticCacheService service = new FaqSemanticCacheService(properties,cache);

    @BeforeEach void enableOfficialAnswerStore() { cache.setEnabled(true); }

    @Test void semanticCacheDefaultsToDisabled() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isReviewedPolicyEnabled()).isFalse();
        assertThat(new FaqCacheProperties().isEnabled()).isFalse();
        assertThat(service.shouldSearch("eSIM이 무엇인가요?", false)).isFalse();
    }

    @Test void rejectsKnownPartialAnswerCasesButAllowsSingleDualSimIntent() {
        properties.setEnabled(true);
        for (String question : List.of(
                "esim이 어떤 건지 설명해줘. 그리고 발급 가격도 알려줘.",
                "eSIM이랑 유심을 함께 쓸 수 있나요? 그리고 해외 로밍 신청은 어디서 하나요?",
                "실물 심과 같이 쓰는 게 아니라 쓰던 유심을 이심으로 전환할 수 있나요?",
                "그거 신청은 어떻게 해요?", "제 eSIM 발급 확인해줘")) {
            assertThat(service.shouldSearch(question,false)).as(question).isFalse();
        }
        assertThat(service.shouldSearch("안녕하세요! eSIM이랑 유심을 함께 쓸 수 있나요?",false)).isTrue();
        assertThat(service.shouldSearch("esim이 뭐야!",false)).isTrue();
        assertThat(service.shouldSearch("esim이 뭐야!",true)).isFalse();
        cache.setEnabled(false);
        assertThat(service.shouldSearch("esim이 뭐야!",false)).isFalse();
    }

    @Test void unapprovedCompetitorOrSmallGapBlocksCache() {
        properties.setEnabled(true);
        assertThat(service.select(1L,List.of(candidate(.98,false),candidate(.7,true)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.98,true),candidate(.9,false)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.94,true),candidate(.7,false)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(Double.NaN,true),candidate(.7,false)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.98,true)))).isEmpty();
        assertThat(service.select(1L,List.of(candidate(.98,true),candidate(.7,false)))).isPresent();
    }

    private SemanticFaqCandidateResponseDto candidate(double score, boolean registered) {
        return new SemanticFaqCandidateResponseDto(new FaqSearchResponseDto(registered?14L:15L,"공식 질문","공식 답변",
                score,Intent.GENERAL,registered,1),"비교 표현",registered);
    }
}
