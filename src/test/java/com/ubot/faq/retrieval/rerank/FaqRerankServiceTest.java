package com.ubot.faq.retrieval.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

@DisplayName("FAQ 재정렬 서비스")
class FaqRerankServiceTest {

    @Test
    @DisplayName("Reranker 점수가 높은 순서로 놓고 벡터 검색 순위를 함께 남긴다")
    void sortsByScore() {
        List<RerankedFaq> reranked = service(0.2, 0.9, 0.5).rerank("질문", List.of(faq(11L), faq(12L), faq(13L)));

        assertThat(reranked).extracting(item -> item.faq().faqId()).containsExactly(12L, 13L, 11L);
        assertThat(reranked).extracting(RerankedFaq::rerankScore).containsExactly(0.9, 0.5, 0.2);
        assertThat(reranked).extracting(RerankedFaq::denseRank).containsExactly(2, 3, 1);
    }

    @Test
    @DisplayName("점수가 같으면 벡터 검색 순위가 앞선 후보를 먼저 놓는다")
    void tieKeepsDenseOrder() {
        List<RerankedFaq> reranked = service(0.5, 0.5, 0.5).rerank("질문", List.of(faq(30L), faq(10L), faq(20L)));

        assertThat(reranked).extracting(item -> item.faq().faqId()).containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("후보가 없으면 Reranker를 호출하지 않는다")
    void emptyCandidates() {
        FaqRerankService service = new FaqRerankService((query, documents) -> {
            throw new AssertionError("호출하면 안 됩니다.");
        });

        assertThat(service.rerank("질문", List.of())).isEmpty();
    }

    @Test
    @DisplayName("점수 개수가 후보 수와 다르면 쓸 수 없는 응답으로 본다")
    void wrongScoreCount() {
        assertThatThrownBy(() -> service(0.9).rerank("질문", List.of(faq(1L), faq(2L))))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("점수가 비었거나 유한한 수가 아니면 쓸 수 없는 응답으로 본다")
    void nonFiniteScore() {
        assertThatThrownBy(() -> service(0.9, null).rerank("질문", List.of(faq(1L), faq(2L))))
                .isInstanceOf(RerankException.class);
        assertThatThrownBy(() -> service(0.9, Double.POSITIVE_INFINITY).rerank("질문", List.of(faq(1L), faq(2L))))
                .isInstanceOf(RerankException.class);
    }

    private FaqRerankService service(Double... scores) {
        return new FaqRerankService((query, documents) -> Arrays.asList(scores));
    }

    private FaqSearchResponseDto faq(Long id) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "FAQ 답변 " + id, 0.8, Intent.GENERAL);
    }
}
