package com.ubot.faq.retrieval;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.rerank.FaqRerankService;
import com.ubot.faq.retrieval.rerank.RerankException;
import com.ubot.faq.retrieval.rerank.RerankedFaq;
import com.ubot.faq.service.FaqVectorService;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 답변에 쓸 FAQ 후보를 찾습니다. 벡터 검색을 하고, 설정으로 켰을 때만 후보를 Reranker로 다시 정렬합니다.
 *
 * <ul>
 *   <li>Reranker 끔(기본값): 벡터 검색 상위 finalK개. 기존 동작과 같습니다.</li>
 *   <li>Reranker 켬: 벡터 검색 상위 candidateK개를 Reranker 점수 순으로 다시 정렬합니다.</li>
 *   <li>Reranker 켬, 호출 실패: 이미 받아 둔 벡터 검색 상위 finalK개. 검색을 다시 하지 않습니다.</li>
 * </ul>
 *
 * 유사도 기준(CHAT_CONFIDENCE_THRESHOLD)은 호출한 쪽이 코사인 유사도에 적용합니다. Reranker 점수는 순서를 정하는 데만 씁니다.
 */
@Slf4j
@Service
public class FaqRetrievalService {

    private final FaqVectorService faqVectorService;
    private final FaqRerankService faqRerankService;
    private final FaqRetrievalProperties.Rerank rerank;

    public FaqRetrievalService(
            FaqVectorService faqVectorService,
            FaqRerankService faqRerankService,
            FaqRetrievalProperties properties,
            @Value("${CHAT_TOP_K:3}") int chatTopK
    ) {
        this.faqVectorService = faqVectorService;
        this.faqRerankService = faqRerankService;
        this.rerank = properties.getRerank();

        if (rerank.isEnabled() && rerank.getCandidateK() < chatTopK) {
            throw new IllegalStateException(
                    "FAQ_RERANK_CANDIDATE_K는 CHAT_TOP_K 이상이어야 합니다: 후보수="
                            + rerank.getCandidateK() + ", CHAT_TOP_K=" + chatTopK);
        }

        log.info("FAQ Reranker 설정을 읽었습니다: 사용={}, 후보수={}, 모델={}, 연결제한={}, 응답제한={}, 실패시벡터결과사용={}",
                rerank.isEnabled(), rerank.getCandidateK(), rerank.getModel(),
                rerank.getConnectTimeout(), rerank.getReadTimeout(), rerank.isFallbackEnabled());
    }

    /** Reranker를 쓰지 않는 기본 설정의 서비스입니다. 벡터 검색만 필요한 곳(테스트 등)에서 씁니다. */
    public static FaqRetrievalService denseOnly(FaqVectorService faqVectorService) {
        return new FaqRetrievalService(faqVectorService, null, new FaqRetrievalProperties(), 3);
    }

    /**
     * 사용자 질문에 맞는 FAQ 후보를 찾습니다.
     *
     * @param intent 재검색에서 사용자가 고른 의도. null이면 전체 FAQ에서 찾습니다.
     * @param finalK 최종으로 LLM에 전달할 최대 FAQ 수
     * @throws CancellationException Reranker를 기다리는 중에 작업이 취소된 경우
     * @throws RerankException Reranker를 쓰지 못했고 벡터 검색 결과로 답하는 설정을 꺼 둔 경우
     */
    public RetrievalOutcome retrieve(String userQuestion, Intent intent, int finalK) {
        if (!rerank.isEnabled()) {
            // 기존 경로를 그대로 탑니다. 결과, 순위, 유사도가 이전과 같아야 합니다.
            return RetrievalOutcome.dense(search(userQuestion, intent, finalK));
        }

        long startedAt = System.nanoTime();
        int candidateK = Math.max(rerank.getCandidateK(), finalK);
        // DB·임베딩 오류는 여기서 잡지 않습니다. Reranker 실패로 감추지 않고 기존 검색 실패 처리로 넘깁니다.
        List<FaqSearchResponseDto> dense = search(userQuestion, intent, candidateK);
        long denseMillis = elapsedMillis(startedAt);
        if (dense == null || dense.isEmpty()) {
            return RetrievalOutcome.dense(dense);
        }

        long rerankStartedAt = System.nanoTime();
        List<RerankedFaq> reranked;
        try {
            reranked = faqRerankService.rerank(userQuestion, dense);
        } catch (RerankException exception) {
            if (Thread.currentThread().isInterrupted()) {
                // 작업이 취소된 것이므로 대체 결과를 내지 않고 취소를 알립니다.
                throw new CancellationException("FAQ 재정렬 중에 작업이 취소되었습니다.");
            }
            if (!rerank.isFallbackEnabled()) {
                throw exception;
            }
            log.warn("Reranker를 쓰지 못해 벡터 검색 결과로 답합니다: 후보수={}, 최종수={}, 벡터검색={}ms, Reranker={}ms, 사유={}",
                    dense.size(), finalK, denseMillis, elapsedMillis(rerankStartedAt), exception.getMessage());
            return RetrievalOutcome.fallback(
                    dense.stream().limit(finalK).toList(), dense.get(0), exception.getMessage());
        }

        log.info("FAQ 후보를 Reranker로 다시 정렬했습니다: 후보수={}, 최종수={}, 벡터검색={}ms, Reranker={}ms, 순서=[{}]",
                dense.size(), finalK, denseMillis, elapsedMillis(rerankStartedAt), describe(reranked));

        return RetrievalOutcome.reranked(reranked.stream().map(RerankedFaq::faq).toList(), dense.get(0));
    }

    private List<FaqSearchResponseDto> search(String userQuestion, Intent intent, int size) {
        return intent == null
                ? faqVectorService.getSimilarList(userQuestion, size)
                : faqVectorService.getSimilarListByIntent(userQuestion, intent, size);
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
