package com.ubot.faq.service;

import java.util.List;

import com.ubot.common.ErrorCode;
import com.ubot.common.exception.FaqException;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FaqVectorService {
    private final EmbeddingService embeddingService;
    private final FaqVectorRepository faqVectorRepository;

    public List<FaqSearchResponseDto> getSimilarList(String userQuestion, int topK) {
        PGvector queryVector = embeddingService.embedText(userQuestion);
        return faqVectorRepository.getSimilarList(queryVector, topK);
    }

    public void saveVectorForFaq(Long faqId, String question) {
        PGvector vector = embeddingService.embedText(question);
        faqVectorRepository.saveVectorForFaq(faqId, vector);
    }

    public void saveVectorForOldFaq(Long faqId, Integer version) {
        PGvector vector = faqVectorRepository.findVectorByFaqId(faqId);
        if(vector == null) {
            throw new FaqException(ErrorCode.FAQ_VECTOR_CREATE_FAILURE);
        }
        faqVectorRepository.saveVectorForOldFaq(faqId, version, vector);
    }
}