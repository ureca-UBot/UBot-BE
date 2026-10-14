package com.ubot.faq.retrieval.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;

import lombok.RequiredArgsConstructor;

/** FAQ 후보의 질문+답변을 Reranker로 점수화해 순서만 바꿉니다. 후보를 더하거나 빼지 않습니다. */
@Service
@RequiredArgsConstructor
public class FaqRerankService {

    private final RerankerClient rerankerClient;

    /**
     * 후보를 Reranker 점수가 높은 순서로 돌려줍니다. 점수가 같으면 벡터 검색 순위, 그다음 FAQ ID 순서를 따릅니다.
     *
     * @param candidates 벡터 검색 순위대로 놓인 후보
     * @throws RerankException Reranker 호출 실패 또는 쓸 수 없는 응답
     */
    public List<RerankedFaq> rerank(String query, List<FaqSearchResponseDto> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> documents = candidates.stream()
                // FAQ를 임베딩할 때와 같은 "질문: ...\n답변: ..." 형식으로 보냅니다.
                .map(candidate -> FaqVectorType.QUESTION_ANSWER.toEmbeddingText(candidate.question(), candidate.answer()))
                .toList();

        List<Double> scores = rerankerClient.score(query, documents);
        if (scores == null || scores.size() != candidates.size()) {
            throw new RerankException("Reranker 점수 개수가 후보 수와 다릅니다.");
        }

        List<RerankedFaq> reranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Double score = scores.get(index);
            if (score == null || !Double.isFinite(score)) {
                throw new RerankException("Reranker 점수가 유한한 수가 아닙니다: FAQID=" + candidates.get(index).faqId());
            }
            // 점수는 입력 자리로 맞추므로 FAQ의 질문·답변·의도·코사인 유사도는 벡터 검색 결과 그대로입니다.
            reranked.add(new RerankedFaq(candidates.get(index), score, index + 1));
        }
        reranked.sort(Comparator.comparingDouble(RerankedFaq::rerankScore).reversed()
                .thenComparingInt(RerankedFaq::denseRank)
                .thenComparing(item -> item.faq().faqId()));
        return reranked;
    }
}
