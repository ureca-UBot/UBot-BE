package com.ubot.faq.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

@DisplayName("FAQ 답변 문장 유사도 필터")
class FaqSentenceFilterServiceTest {
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final FaqSentenceFilterService service = new FaqSentenceFilterService(embeddingService);

    @Test
    @DisplayName("문장부호로 나누고 줄바꿈은 이어 붙여 기준을 통과한 문장만 FAQ 순서대로 반환한다")
    void keepsOnlySimilarSentencesInFaqOrder() {
        ReflectionTestUtils.setField(service, "threshold", 0.7);
        FaqSearchResponseDto first = faq(1L, "가입은 앱에서 합니다. 요금은 100원입니다! 해지는 고객센터에서 합니다?");
        FaqSearchResponseDto second = faq(2L, "첫째 줄\n둘째 줄입니다.");
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(
                vector(1, 0), vector(0.8f, 0.6f), vector(0, 1),
                vector(1, 0), vector(1, 0)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter("가입 방법", List.of(first, second));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly(
                "가입 방법", "가입은 앱에서 합니다.", "요금은 100원입니다!",
                "해지는 고객센터에서 합니다?", "첫째 줄 둘째 줄입니다.");
        assertThat(result).extracting(item -> item.faq().faqId()).containsExactly(1L, 2L);
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences)
                .containsExactly("가입은 앱에서 합니다.\n해지는 고객센터에서 합니다?", "첫째 줄 둘째 줄입니다.");
    }

    @Test
    @DisplayName("모든 문장이 유사도 기준에 미달하면 FAQ를 반환하지 않는다")
    void excludesFaqWithoutPassingSentences() {
        ReflectionTestUtils.setField(service, "threshold", 0.7);
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(1, 0), vector(0, 1)));

        assertThat(service.filter("가입 방법", List.of(faq(1L, "요금은 100원입니다.")))).isEmpty();
    }

    @Test
    @DisplayName("답변에 문장이 없으면 임베딩 서비스를 호출하지 않는다")
    void skipsEmbeddingWhenAnswersAreBlank() {
        assertThat(service.filter("가입 방법", List.of(faq(1L, "  \n  "), faq(2L, null)))).isEmpty();
        verifyNoInteractions(embeddingService);
    }

    private FaqSearchResponseDto faq(Long id, String answer) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, answer, 0.8, Intent.GENERAL);
    }

    private PGvector vector(float first, float second) {
        return new PGvector(new float[] {first, second});
    }
}
