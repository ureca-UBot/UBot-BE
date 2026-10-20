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

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

@DisplayName("FAQ 답변 문장 유사도 필터")
class FaqSentenceFilterServiceTest {
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final FaqSentenceFilterService service = new FaqSentenceFilterService(embeddingService, 0.7);
    private final PGvector queryVector = vector(1, 0);

    @Test
    @DisplayName("문장부호로 나누고 줄바꿈은 이어 붙여 기준을 통과한 문장만 FAQ 순서대로 반환한다")
    void keepsOnlySimilarSentencesInFaqOrder() {
        FaqSearchResponseDto first = faq(1L, "가입은 앱에서 합니다. 요금은 100원입니다! 해지는 고객센터에서 합니다?");
        FaqSearchResponseDto second = faq(2L, "첫째 줄\n둘째 줄입니다.");
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(
                vector(1, 0), vector(0, 1), vector(1, 0),
                vector(1, 0)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter(queryVector, List.of(first, second));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly(
                "가입은 앱에서 합니다.", "요금은 100원입니다!",
                "해지는 고객센터에서 합니다?", "첫째 줄 둘째 줄입니다.");
        assertThat(result).extracting(item -> item.faq().faqId()).containsExactly(1L, 2L);
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences)
                .containsExactly("가입은 앱에서 합니다.\n해지는 고객센터에서 합니다?", "첫째 줄 둘째 줄입니다.");
    }

    @Test
    @DisplayName("모든 문장이 유사도 기준에 미달하면 FAQ를 반환하지 않는다")
    void excludesFaqWithoutPassingSentences() {
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(0, 1)));

        assertThat(service.filter(queryVector, List.of(faq(1L, "요금은 100원입니다.")))).isEmpty();
    }

    @Test
    @DisplayName("답변에 문장이 없으면 임베딩 서비스를 호출하지 않는다")
    void skipsEmbeddingWhenAnswersAreBlank() {
        assertThat(service.filter(queryVector, List.of(faq(1L, "  \n  "), faq(2L, null)))).isEmpty();
        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("번호 목록은 앞 문장과 묶고 뒤의 일반 문장은 따로 비교한다")
    void groupsNumberedListAndSeparatesFollowingSentence() {
        String answer = "다음 방법으로 신청할 수 있습니다.\n1. 앱 접속\n2. 메뉴 선택\n3. 신청하기\n이러한 방법으로 해결할 수 있습니다.";
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(1, 0), vector(0, 1)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter(queryVector, List.of(faq(1L, answer)));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly(
                "다음 방법으로 신청할 수 있습니다. 앱 접속 메뉴 선택 신청하기",
                "이러한 방법으로 해결할 수 있습니다.");
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences)
                .containsExactly("다음 방법으로 신청할 수 있습니다.\n1. 앱 접속\n2. 메뉴 선택\n3. 신청하기");
    }

    @Test
    @DisplayName("글머리 목록은 한 묶음으로 비교하고 원래 줄바꿈을 유지한다")
    void groupsBulletList() {
        String answer = "- 선택약정 할인: 요금의 25%\n- 단말 지원금: 기기별 상이\n- 위약금: 잔여 기간 기준";
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(1, 0)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter(queryVector, List.of(faq(1L, answer)));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly("선택약정 할인: 요금의 25% 단말 지원금: 기기별 상이 위약금: 잔여 기간 기준");
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences).containsExactly(answer);
    }

    @Test
    @DisplayName("1.5GB처럼 숫자 뒤에 공백 없이 이어지는 값은 목록 항목으로 보지 않는다")
    void keepsDecimalValueSeparateFromPrecedingList() {
        String answer = "- 추가 데이터 제공\n1.5GB가 제공됩니다.";
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(0, 1), vector(1, 0)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter(queryVector, List.of(faq(1L, answer)));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly("추가 데이터 제공", "1.5GB가 제공됩니다.");
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences)
                .containsExactly("1.5GB가 제공됩니다.");
    }

    @Test
    @DisplayName("안내 문장과 목록 사이에 빈 줄이 있어도 한 묶음으로 비교한다")
    void groupsIntroAndListAcrossBlankLine() {
        String answer = "신청 방법은 다음과 같습니다.\n\n1. 앱 접속\n2. 메뉴 선택";
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(1, 0)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter(queryVector, List.of(faq(1L, answer)));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly("신청 방법은 다음과 같습니다. 앱 접속 메뉴 선택");
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences).containsExactly(answer);
    }

    @Test
    @DisplayName("들여 쓴 줄은 직전 목록 항목의 이어지는 내용으로 비교한다")
    void groupsIndentedContinuationWithList() {
        String answer = "1. 앱 접속\n  본인 인증 진행\n2. 신청 완료\n안내 종료.";
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(vector(1, 0), vector(0, 1)));

        List<FaqSentenceFilterService.MatchedFaq> result = service.filter(queryVector, List.of(faq(1L, answer)));

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.captor();
        verify(embeddingService).embedTexts(texts.capture());
        assertThat(texts.getValue()).containsExactly("앱 접속 본인 인증 진행 신청 완료", "안내 종료.");
        assertThat(result).extracting(FaqSentenceFilterService.MatchedFaq::answerSentences)
                .containsExactly("1. 앱 접속\n본인 인증 진행\n2. 신청 완료");
    }

    @Test
    @DisplayName("기준값과 같으면 통과하고 차원이 다른 벡터는 제외하며 통과 FAQ의 순서를 유지한다")
    void handlesBoundaryMismatchAndFaqOrder() {
        FaqSentenceFilterService boundary = new FaqSentenceFilterService(embeddingService, 1.0);
        when(embeddingService.embedTexts(anyList())).thenReturn(List.of(
                vector(1, 0), vector(0, 1), new PGvector(new float[] {1, 0, 0}), vector(1, 0)));

        List<FaqSentenceFilterService.MatchedFaq> result = boundary.filter(queryVector, List.of(
                faq(1L, "경계 문장입니다."), faq(2L, "미달 문장입니다."),
                faq(3L, "차원 불일치 문장입니다."), faq(4L, "통과 문장입니다.")));

        assertThat(result).extracting(item -> item.faq().faqId()).containsExactly(1L, 4L);
    }

    private FaqSearchResponseDto faq(Long id, String answer) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, answer, 0.8, Intent.GENERAL);
    }

    private PGvector vector(float first, float second) {
        return new PGvector(new float[] {first, second});
    }
}
