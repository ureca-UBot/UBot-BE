package com.ubot.faq.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

import java.util.List;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqSentenceFilterService;

/** 기존 채팅 통합 테스트의 FAQ 응답을 새 검색·문장 필터 경로에 연결합니다. */
public final class FaqSearchMockSupport {
    private static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});

    private FaqSearchMockSupport() {
    }

    public static void bridgeSearch(FaqVectorService vector) {
        doAnswer(invocation -> {
            String question = invocation.getArgument(0);
            Intent intent = invocation.getArgument(1);
            int topK = invocation.getArgument(2);
            List<FaqSearchResponseDto> faqs = intent == null
                    ? vector.getSimilarList(question, topK)
                    : vector.getSimilarListByIntent(question, intent, topK);
            return new FaqVectorService.SearchResult(faqs, QUERY_VECTOR);
        }).when(vector).searchWithVector(anyString(), nullable(Intent.class), anyInt());
    }

    public static void passAnswers(FaqSentenceFilterService filter) {
        doAnswer(invocation -> {
            List<FaqSearchResponseDto> faqs = invocation.getArgument(1);
            return faqs.stream()
                    .map(faq -> new FaqSentenceFilterService.MatchedFaq(faq, faq.answer()))
                    .toList();
        }).when(filter).filter(any(PGvector.class), anyList());
    }
}
