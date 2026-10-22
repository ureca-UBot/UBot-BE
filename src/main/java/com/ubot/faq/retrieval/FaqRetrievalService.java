package com.ubot.faq.retrieval;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.rerank.FaqRerankService;
import com.ubot.faq.retrieval.rerank.RerankCallGuard;
import com.ubot.faq.retrieval.rerank.RerankException;
import com.ubot.faq.retrieval.rerank.RerankSkippedException;
import com.ubot.faq.retrieval.rerank.RerankedFaq;
import com.ubot.faq.service.FaqVectorService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 답변에 쓸 FAQ 후보를 질문 벡터로 찾고, 설정에 따라 Reranker로 다시 정렬합니다.
 *
 * <ul>
 *   <li>Reranker를 끄면 벡터 검색 순서를 사용합니다.</li>
 *   <li>Reranker를 켜면 후보를 다시 정렬하고, 실패 시 설정에 따라 벡터 검색 순서로 돌아갑니다.</li>
 * </ul>
 *
 * 채팅 답변 경로는 캐시 실패 후 정렬된 후보 FAQ의 답변 문장을 비교합니다.
 */
@Slf4j
@Service
public class FaqRetrievalService {

    private final FaqVectorService faqVectorService;
    private final FaqRerankService faqRerankService;
    private final FaqRetrievalProperties.Rerank rerank;
    private final RerankCallGuard rerankCallGuard;
    // 지표를 보지 않는 테스트는 전역 레지스트리를 씀. 등록된 저장소가 없으면 기록하지 않음
    private final MeterRegistry metrics;

    public FaqRetrievalService(
            FaqVectorService faqVectorService,
            FaqRerankService faqRerankService,
            FaqRetrievalProperties properties,
            int chatTopK
    ) {
        this(faqVectorService, faqRerankService, properties, chatTopK, Metrics.globalRegistry);
    }

    @Autowired
    public FaqRetrievalService(
            FaqVectorService faqVectorService,
            FaqRerankService faqRerankService,
            FaqRetrievalProperties properties,
            @Value("${CHAT_TOP_K:3}") int chatTopK,
            MeterRegistry metrics
    ) {
        this.metrics = metrics;
        this.faqVectorService = faqVectorService;
        this.faqRerankService = faqRerankService;
        this.rerank = properties.getRerank();
        this.rerankCallGuard = new RerankCallGuard(
                rerank.getMaxConcurrent(), rerank.getFailureThreshold(), rerank.getFailureCooldown());

        if (rerank.isEnabled() && rerank.getCandidateK() < chatTopK) {
            throw new IllegalStateException(
                    "FAQ_RERANK_CANDIDATE_K는 CHAT_TOP_K 이상이어야 합니다: 후보수="
                            + rerank.getCandidateK() + ", CHAT_TOP_K=" + chatTopK);
        }

        log.info("FAQ Reranker 설정을 읽었습니다: 사용={}, 후보수={}, 모델={}, 연결제한={}, 응답제한={}, 실패시벡터결과사용={}, "
                        + "동시호출한도={}, 연속실패한도={}, 건너뛰는시간={}",
                rerank.isEnabled(), rerank.getCandidateK(), rerank.getModel(),
                rerank.getConnectTimeout(), rerank.getReadTimeout(), rerank.isFallbackEnabled(),
                rerank.getMaxConcurrent(), rerank.getFailureThreshold(), rerank.getFailureCooldown());
    }

    /** Reranker를 쓰지 않는 기본 설정의 서비스입니다. 벡터 검색만 필요한 곳(테스트 등)에서 씁니다. */
    public static FaqRetrievalService denseOnly(FaqVectorService faqVectorService) {
        return new FaqRetrievalService(faqVectorService, null, new FaqRetrievalProperties(), 3);
    }

    /**
     * 임베딩과 DB 검색을 한 번 수행합니다.
     * 캐시 승인 여부로 후보를 미리 거르지 않습니다.
     *
     * @param intent 재검색에서 사용자가 고른 의도. null이면 전체 FAQ에서 찾습니다.
     * @param finalK 최종으로 LLM에 전달할 최대 FAQ 수
     * @param minimumCandidates 캐시 점수 차이 검사용 최소 후보 수. 캐시를 사용하지 않으면 0입니다.
     */
    public DenseFaqCandidates searchCandidates(String userQuestion, Intent intent, int finalK, int minimumCandidates) {
        long startedAt = System.nanoTime();
        int candidateK = Math.max(finalK, minimumCandidates);
        if (rerank.isEnabled()) {
            candidateK = Math.max(candidateK, rerank.getCandidateK());
        }
        // DB·임베딩 오류는 호출한 쪽의 검색 실패 처리로 넘깁니다.
        FaqVectorService.SearchResult dense = faqVectorService.searchWithVector(userQuestion, intent, candidateK);
        recordTopSimilarity(dense.faqs(), intent);
        return new DenseFaqCandidates(dense.faqs(), elapsedMillis(startedAt), dense.queryVector());
    }

    // 1위 후보의 코사인 유사도 분포. 구간은 application.yml의 slo 설정을 따름. 후보가 없으면 NO_FAQ로 따로 셈
    private void recordTopSimilarity(List<FaqSearchResponseDto> candidates, Intent intent) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        metrics.summary("faq.retrieval.top.similarity", "scope", intent == null ? "all" : "intent")
                .record(candidates.getFirst().similarityScore());
    }

    /**
     * 이미 검색한 후보를 사용합니다. 캐시 MISS 후에도 임베딩·검색을 다시 호출하지 않습니다.
     *
     * @throws CancellationException Reranker를 기다리는 중에 작업이 취소된 경우
     * @throws RerankException Reranker를 쓰지 못했고 벡터 검색 결과로 답하는 설정을 꺼 둔 경우
     */
    public RetrievalOutcome rankCandidates(String userQuestion, DenseFaqCandidates candidates, int finalK) {
        List<FaqSearchResponseDto> searched = candidates.candidates();
        int rankingCandidateK = rerank.isEnabled() ? Math.max(rerank.getCandidateK(), finalK) : finalK;
        // 캐시 비교용으로 더 읽은 후보는 기존 리랭커 후보 범위나 LLM 전달 개수를 늘리지 않습니다.
        List<FaqSearchResponseDto> dense = searched == null || searched.size() <= rankingCandidateK
                ? searched : searched.stream().limit(rankingCandidateK).toList();
        long denseMillis = candidates.searchMillis();
        if (dense == null || dense.isEmpty()) {
            return RetrievalOutcome.dense(dense);
        }
        if (!rerank.isEnabled()) {
            return RetrievalOutcome.dense(dense);
        }

        long rerankStartedAt = System.nanoTime();
        List<RerankedFaq> reranked;
        try {
            reranked = rerankCallGuard.call(() -> faqRerankService.rerank(userQuestion, dense));
        } catch (RerankException exception) {
            if (Thread.currentThread().isInterrupted()) {
                // 작업이 취소된 것이므로 대체 결과를 내지 않고 취소를 알립니다.
                throw new CancellationException("FAQ 재정렬 중에 작업이 취소되었습니다.");
            }
            if (!rerank.isFallbackEnabled()) {
                throw exception;
            }
            if (exception instanceof RerankSkippedException) {
                // 요청이 몰릴 때 요청마다 경고를 남기면 로그가 넘치므로 낮은 수준으로 남깁니다. 건너뛰기 시작과 재개는 RerankCallGuard가 알립니다.
                log.debug("Reranker를 호출하지 않고 벡터 검색 결과로 답합니다: 사유={}", exception.getMessage());
            } else {
                log.warn("Reranker를 쓰지 못해 벡터 검색 결과로 답합니다: 후보수={}, 최종수={}, 벡터검색={}ms, Reranker={}ms, 사유={}",
                        dense.size(), finalK, denseMillis, elapsedMillis(rerankStartedAt), exception.getMessage());
            }
            return RetrievalOutcome.fallback(
                    dense.stream().limit(finalK).toList(), dense.get(0), exception.getMessage());
        }

        log.info("FAQ 후보를 Reranker로 다시 정렬했습니다: 후보수={}, 최종수={}, 벡터검색={}ms, Reranker={}ms, 순서=[{}]",
                dense.size(), finalK, denseMillis, elapsedMillis(rerankStartedAt), describe(reranked));

        return RetrievalOutcome.reranked(reranked.stream().map(RerankedFaq::faq).toList(), dense.get(0));
    }

    /** 질문 원문은 남기지 않고 FAQ ID, 점수, 순위 변화만 남깁니다. */
    private String describe(List<RerankedFaq> reranked) {
        return reranked.stream()
                .map(item -> "FAQ ID=" + item.faq().faqId()
                        + ", Reranker점수=" + item.rerankScore()
                        + ", 유사도=" + item.faq().similarityScore()
                        + ", 벡터순위=" + item.denseRank())
                .collect(Collectors.joining(" | "));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
