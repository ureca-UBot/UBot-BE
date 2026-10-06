package com.ubot.faq.service;

import java.util.List;

import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqVectorService {
    private final EmbeddingService embeddingService;
    private final FaqVectorRepository faqVectorRepository;

	public List<FaqSearchResponseDto> getSimilarList(String userQuestion, int topK) {
		long startedAt = System.nanoTime();
		PGvector queryVector = embeddingService.embedText(userQuestion);
		List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, topK);
		log.debug("FAQ 벡터 검색을 완료했습니다: 처리시간={}ms, 검색결과=[{}]", elapsedMillis(startedAt),
				formatSearchResults(results));
		return results;
	}

	public void saveVectorForFaq(Long faqId, String question) {
		long startedAt = System.nanoTime();
		PGvector vector = embeddingService.embedText(question);
		faqVectorRepository.saveVectorForFaq(faqId, vector);
		log.info("FAQ 벡터를 저장했습니다: FAQID={}, 처리시간={}ms", faqId, elapsedMillis(startedAt));
    }

    public void saveVectorForOldFaq(Long faqId, Integer version) {
        PGvector vector = faqVectorRepository.findVectorByFaqId(faqId);
        if(vector == null) {
            throw new FaqException(FaqErrorCode.FAQ_VECTOR_CREATE_FAILURE);
        }
		faqVectorRepository.saveVectorForOldFaq(faqId, version, vector);
		log.info("이전 FAQ 벡터를 저장했습니다: FAQID={}, 버전={}", faqId, version);
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	private String formatSearchResults(List<FaqSearchResponseDto> results) {
		if (results == null || results.isEmpty()) {
			return "없음";
		}
		return results.stream()
				.map(result -> "FAQ ID=" + result.faqId()
						+ ", FAQ 질문=" + normalizeForLog(result.question())
						+ ", 유사도=" + result.similarityScore())
				.collect(java.util.stream.Collectors.joining(" | "));
	}

	private String normalizeForLog(String value) {
		return value == null ? "없음" : value.replaceAll("[\\r\\n\\t]+", " ");
	}
}
