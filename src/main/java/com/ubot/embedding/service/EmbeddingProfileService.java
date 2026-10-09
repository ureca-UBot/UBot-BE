package com.ubot.embedding.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public Long getCurrentProfileId() {
        AiEngine engine = aiRuntimeProperties.resolvedEmbeddingEngine();

        return embeddingProfileRepository.findOrCreate(
                toProvider(engine),
                embeddingClient.getModelName(),
                EmbeddingClient.DIMENSIONS,
                aiRuntimeProperties.getEmbeddingProfileVersion()
        );
    }

    private String toProvider(AiEngine engine) {
        return switch (engine) {
            case OLLAMA -> "ollama";
            case VLLM -> "openai-compatible";
        };
    }
}