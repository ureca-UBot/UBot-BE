package com.ubot.embedding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiEngine;
import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.repository.EmbeddingProfileRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmbeddingProfileServiceTest {

    @Mock
    private AiRuntimeProperties aiRuntimeProperties;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private EmbeddingProfileRepository embeddingProfileRepository;

    @InjectMocks
    private EmbeddingProfileService service;

    @Test
    void resolvesOllamaProfile() {
        when(aiRuntimeProperties.resolvedEmbeddingEngine())
                .thenReturn(AiEngine.OLLAMA);

        when(aiRuntimeProperties.getEmbeddingProfileVersion())
                .thenReturn(1);

        when(embeddingClient.getModelName())
                .thenReturn("bge-m3:567m");

        when(embeddingProfileRepository.findOrCreate(
                "ollama",
                "bge-m3:567m",
                EmbeddingClient.DIMENSIONS,
                1
        )).thenReturn(10L);

        Long profileId = service.getCurrentProfileId();

        assertThat(profileId).isEqualTo(10L);

        verify(embeddingProfileRepository).findOrCreate(
                "ollama",
                "bge-m3:567m",
                EmbeddingClient.DIMENSIONS,
                1
        );
    }

    @Test
    void resolvesVllmProfileAsOpenAiCompatible() {
        when(aiRuntimeProperties.resolvedEmbeddingEngine())
                .thenReturn(AiEngine.VLLM);

        when(aiRuntimeProperties.getEmbeddingProfileVersion())
                .thenReturn(1);

        when(embeddingClient.getModelName())
                .thenReturn("ubot-embedding");

        when(embeddingProfileRepository.findOrCreate(
                "openai-compatible",
                "ubot-embedding",
                EmbeddingClient.DIMENSIONS,
                1
        )).thenReturn(20L);

        Long profileId = service.getCurrentProfileId();

        assertThat(profileId).isEqualTo(20L);

        verify(embeddingProfileRepository).findOrCreate(
                "openai-compatible",
                "ubot-embedding",
                EmbeddingClient.DIMENSIONS,
                1
        );
    }
}