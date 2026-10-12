package com.ubot.faq.service;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.request.FaqCacheQuestionCreateRequestDto;
import com.ubot.faq.config.FaqSemanticCacheProperties;
import com.ubot.faq.dto.response.FaqCacheQuestionResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqCacheQuestionRepository;
import com.ubot.faq.repository.FaqCacheQuestionRepository.FaqSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FaqCacheQuestionService {
    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService profileService;
    private final FaqCacheQuestionRepository repository;
    private final FaqSemanticCacheProperties properties;
    private final TransactionTemplate transactions;

    public FaqCacheQuestionService(EmbeddingService embeddingService, EmbeddingProfileService profileService,
                                  FaqCacheQuestionRepository repository, FaqSemanticCacheProperties properties,
                                  PlatformTransactionManager manager) {
        this.embeddingService = embeddingService;
        this.profileService = profileService;
        this.repository = repository;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<FaqCacheQuestionResponseDto> registerFaqCacheQuestions(
            Long faqId, FaqCacheQuestionCreateRequestDto request) {
        Long profileId = profileService.getCurrentProfileId();
        FaqSnapshot snapshot = repository.getActiveFaq(faqId, profileId)
                .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
        validateRegistration(snapshot);
        List<String> questions = request.questions().stream().distinct().toList();
        // The model call happens before a transaction or a FAQ row lock is held.
        List<PGvector> vectors = embedQuestions(questions);
        return transactions.execute(status -> {
            FaqSnapshot current = repository.getActiveFaqForUpdate(faqId, profileId)
                    .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CACHE_FAQ_CHANGED));
            if (!snapshot.equals(current)) {
                throw new FaqException(FaqErrorCode.FAQ_CACHE_FAQ_CHANGED);
            }
            validateRegistration(current);
            List<FaqCacheQuestionResponseDto> registered = new ArrayList<>();
            for (int index = 0; index < questions.size(); index++) {
                registered.add(repository.upsertQuestion(current, profileId, questions.get(index), vectors.get(index)));
            }
            return List.copyOf(registered);
        });
    }

    public List<FaqCacheQuestionResponseDto> getFaqCacheQuestionList(Long faqId) {
        Long profileId = profileService.getCurrentProfileId();
        repository.getActiveFaq(faqId, profileId)
                .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
        return repository.getQuestionList(faqId, profileId);
    }

    public void deactivateFaqCacheQuestion(Long faqId, Long questionId) {
        transactions.executeWithoutResult(status -> {
            if (!repository.lockActiveFaq(faqId)) {
                throw new FaqException(FaqErrorCode.FAQ_NOT_FOUND);
            }
            if (repository.deactivateQuestion(faqId, questionId) == 0) {
                throw new FaqException(FaqErrorCode.FAQ_CACHE_QUESTION_NOT_FOUND);
            }
        });
    }

    private void validateRegistration(FaqSnapshot faq) {
        if (!faq.cacheable() || faq.intent() != Intent.GENERAL) {
            throw new FaqException(FaqErrorCode.FAQ_CACHE_NOT_APPROVED);
        }
        if (!faq.currentVector()) {
            throw new FaqException(FaqErrorCode.FAQ_CACHE_VECTOR_NOT_READY);
        }
    }

    private List<PGvector> embedQuestions(List<String> questions) {
        List<PGvector> vectors = new ArrayList<>();
        int batchSize = properties.getRegistrationBatchSize();
        for (int start = 0; start < questions.size(); start += batchSize) {
            List<String> batch = questions.subList(start, Math.min(start + batchSize, questions.size()));
            List<PGvector> result = embeddingService.embedTexts(batch);
            if (result == null || result.size() != batch.size()) {
                throw new EmbeddingException(EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
            }
            vectors.addAll(result);
        }
        return vectors;
    }
}
