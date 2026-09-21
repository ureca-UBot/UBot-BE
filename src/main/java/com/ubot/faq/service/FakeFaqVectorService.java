package com.ubot.faq.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.FakeFaqSearchResponseDto;
import com.ubot.faq.repository.FakeFaqVectorRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FakeFaqVectorService {
    private final EmbeddingService embeddingService;
    private final FakeFaqVectorRepository faqVectorRepository;

    public List<FakeFaqSearchResponseDto> getSimilarList(String userQuestion, int topK) {
        PGvector queryVector = embeddingService.embedText(userQuestion);
        return faqVectorRepository.getSimilarList(queryVector, topK);
    }

    public void saveEmbedding(Long faqId, String question) {
        PGvector vector = embeddingService.embedText(question);
        faqVectorRepository.saveEmbedding(faqId, vector);
    }
}
