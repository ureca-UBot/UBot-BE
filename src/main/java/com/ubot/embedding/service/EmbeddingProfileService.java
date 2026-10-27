package com.ubot.embedding.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ubot.ai.config.AiEngine;
import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.repository.EmbeddingProfileRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmbeddingProfileService {

    private final AiRuntimeProperties aiRuntimeProperties;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProfileRepository embeddingProfileRepository;

    // 설정은 실행 중에 바뀌지 않으므로 Profile ID는 한 번 조회한 뒤 기억한다.
    private volatile Long cachedProfileId;

    public Long getCurrentProfileId() {
        Long cached = cachedProfileId;

        if (cached != null) {
            return cached;
        }

        AiEngine engine = aiRuntimeProperties.resolvedEmbeddingEngine();

        Long profileId = embeddingProfileRepository.findOrCreate(
                toProvider(engine),
                embeddingClient.getModelName(),
                EmbeddingClient.DIMENSIONS,
                aiRuntimeProperties.getEmbeddingProfileVersion()
        );

        rememberAfterCommit(profileId);

        return profileId;
    }

    // 호출한 쪽의 트랜잭션이 롤백되면 방금 만든 Profile 행도 함께 사라진다.
    // 없는 Profile ID를 기억하지 않도록, 진행 중인 트랜잭션이 있으면 커밋된 뒤에 기억한다.
    private void rememberAfterCommit(Long profileId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cachedProfileId = profileId;
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cachedProfileId = profileId;
                    }
                }
        );
    }

    private String toProvider(AiEngine engine) {
        return switch (engine) {
            case OLLAMA -> "ollama";
            case VLLM -> "openai-compatible";
        };
    }
}