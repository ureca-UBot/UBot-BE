package com.ubot.faq.service;

import com.ubot.faq.enums.FaqVectorType;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.request.FaqCacheQuestionCreateRequestDto;
import com.ubot.faq.config.FaqCacheProperties;
import com.ubot.faq.dto.response.FaqCacheQuestionResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCacheQuestion;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.policy.FaqQuestionNormalizer;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqCacheQuestionRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqVectorRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final FaqRepository faqRepository;
    private final FaqVectorRepository faqVectors;
    private final FaqCacheProperties properties;
    private final TransactionTemplate transactions;

    public FaqCacheQuestionService(EmbeddingService embeddingService, EmbeddingProfileService profileService,
                                  FaqCacheQuestionRepository repository, FaqRepository faqRepository,
                                  FaqVectorRepository faqVectors, FaqCacheProperties properties,
                                  PlatformTransactionManager manager) {
        this.embeddingService = embeddingService;
        this.profileService = profileService;
        this.repository = repository;
        this.faqRepository = faqRepository;
        this.faqVectors = faqVectors;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<FaqCacheQuestionResponseDto> registerFaqCacheQuestions(
            Long faqId, FaqCacheQuestionCreateRequestDto request) {
        Long profileId = profileService.getCurrentProfileId();
        FaqSnapshot snapshot = faqRepository.findActiveCacheRegistrationSnapshotById(faqId)
                .map(faq -> new FaqSnapshot(faq.getId(), faq.getQuestion(), faq.getAnswer(), faq.getVersion(),
                        faq.getIntent(), faq.isCacheable(), faqVectors.hasCurrentVector(faqId, profileId, FaqVectorType.QUESTION, faq.getVersion())))
                .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
        validateRegistration(snapshot);
        Map<String, String> unique = new LinkedHashMap<>();
        for (String question : request.questions()) {
            String normalized = FaqQuestionNormalizer.normalize(question);
            if (normalized == null || normalized.isBlank()) {
                throw new FaqException(FaqErrorCode.FAQ_CACHE_QUESTION_INVALID);
            }
            unique.putIfAbsent(normalized, question);
        }
        // 정규화 키와 원문을 묶어 임베딩 순서와 저장할 입력이 어긋나지 않게 한다.
        List<Map.Entry<String, String>> questions = List.copyOf(unique.entrySet());
        List<String> normalizedQuestions = questions.stream().map(Map.Entry::getKey).toList();
        // The model call happens before a transaction or a FAQ row lock is held.
        List<PGvector> vectors = embedQuestions(normalizedQuestions);
        return transactions.execute(status -> {
            // 같은 FAQ의 등록·비활성화를 직렬화한다. 조회 후 저장 방식에서도 중복 행을 만들지 않는다.
            Faq currentFaq = faqRepository.findActiveByIdForUpdate(faqId)
                    .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CACHE_FAQ_CHANGED));
            FaqSnapshot current = snapshot(currentFaq, profileId);
            if (!snapshot.equals(current)) {
                throw new FaqException(FaqErrorCode.FAQ_CACHE_FAQ_CHANGED);
            }
            validateRegistration(current);
            List<FaqCacheQuestion> registered = new ArrayList<>();
            for (int index = 0; index < questions.size(); index++) {
                Map.Entry<String, String> question = questions.get(index);
                FaqCacheQuestion entity = repository.findByFaq_IdAndNormalizedQuestionAndProfileId(
                                faqId, question.getKey(), profileId)
                        .orElseGet(() -> FaqCacheQuestion.builder().faq(currentFaq).profileId(profileId).build());
                entity.register(question.getValue(), question.getKey(), vectors.get(index).toArray());
                registered.add(repository.save(entity));
            }
            repository.flush();
            return registered.stream()
                    .map(entity -> toResponse(entity, currentFaq, profileId, current.currentVector())).toList();
        });
    }

    public List<FaqCacheQuestionResponseDto> getFaqCacheQuestionList(Long faqId) {
        Long profileId = profileService.getCurrentProfileId();
        return transactions.execute(status -> {
            Faq faq = faqRepository.findActiveById(faqId)
                    .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
            boolean currentVector = faqVectors.hasCurrentVector(faqId, profileId, FaqVectorType.QUESTION, faq.getVersion());
            return repository.findAllByFaq_IdOrderByIdAsc(faqId).stream()
                    .map(entity -> toResponse(entity, faq, profileId, currentVector)).toList();
        });
    }

    public void deactivateFaqCacheQuestion(Long faqId, Long questionId) {
        transactions.executeWithoutResult(status -> {
            faqRepository.findActiveByIdForUpdate(faqId)
                    .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
            FaqCacheQuestion question = repository.findByIdAndFaq_Id(questionId, faqId)
                    .orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CACHE_QUESTION_NOT_FOUND));
            question.deactivate();
        });
    }

    private FaqSnapshot snapshot(Faq faq, Long profileId) {
        return new FaqSnapshot(faq.getId(), faq.getQuestion(), faq.getAnswer(), faq.getVersion(), faq.getIntent(),
                faq.isCacheable(), faqVectors.hasCurrentVector(faq.getId(), profileId, FaqVectorType.QUESTION, faq.getVersion()));
    }

    private FaqCacheQuestionResponseDto toResponse(FaqCacheQuestion question, Faq faq, Long profileId, boolean currentVector) {
        boolean valid = question.isEnabled() && Objects.equals(question.getProfileId(), profileId)
                && question.getNormalizationVersion() == FaqQuestionNormalizer.VERSION
                && faq.getDeletedAt() == null && faq.isCacheable() && faq.getIntent() == Intent.GENERAL
                && question.getFaqVersion() == faq.getVersion()
                && Objects.equals(question.getFaqQuestion(), faq.getQuestion())
                && Objects.equals(question.getFaqAnswer(), faq.getAnswer()) && currentVector;
        return new FaqCacheQuestionResponseDto(question.getId(), faq.getId(), question.getProfileId(),
                question.getQuestion(), question.isEnabled(), valid, question.getFaqVersion(), faq.getVersion(),
                question.getCreatedAt(), question.getNormalizedQuestion(), question.getNormalizationVersion());
    }

    private record FaqSnapshot(Long faqId, String question, String answer, int version,
                               Intent intent, boolean cacheable, boolean currentVector) {
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
