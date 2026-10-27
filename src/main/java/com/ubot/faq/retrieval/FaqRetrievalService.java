package com.ubot.faq.retrieval;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.FaqVectorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/** 질문 벡터로 FAQ 후보를 찾고 검색 순서를 유지합니다. */
@Service
public class FaqRetrievalService {
    private final FaqVectorService faqVectorService;
    private final MeterRegistry metrics;

    public FaqRetrievalService(FaqVectorService faqVectorService) {
        this(faqVectorService, Metrics.globalRegistry);
    }

    @Autowired
    public FaqRetrievalService(FaqVectorService faqVectorService, MeterRegistry metrics) {
        this.faqVectorService = faqVectorService;
        this.metrics = metrics;
    }

    /** 캐시 점수 차이 비교에 필요한 후보까지 한 번에 검색합니다. */
    public DenseFaqCandidates searchCandidates(String question, Intent intent, int finalK, int minimumCandidates) {
        FaqVectorService.SearchResult dense = faqVectorService.searchWithVector(
                question, intent, Math.max(finalK, minimumCandidates));
        if (dense.faqs() != null && !dense.faqs().isEmpty()) {
            metrics.summary("faq.retrieval.top.similarity", "scope", intent == null ? "all" : "intent")
                    .record(dense.faqs().getFirst().similarityScore());
        }
        return new DenseFaqCandidates(dense.faqs(), dense.queryVector());
    }

    /** 캐시 MISS 후에도 다시 검색하지 않고, 답변에 쓸 상위 후보만 선택합니다. */
    public RetrievalOutcome selectCandidates(DenseFaqCandidates candidates, int finalK) {
        List<FaqSearchResponseDto> searched = candidates.candidates();
        List<FaqSearchResponseDto> selected = searched == null || searched.size() <= finalK
                ? searched : searched.stream().limit(finalK).toList();
        return new RetrievalOutcome(selected,
                searched == null || searched.isEmpty() ? null : searched.getFirst());
    }
}
