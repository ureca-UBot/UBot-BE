package com.ubot.embedding.service;

import com.ubot.embedding.client.EmbeddingClient;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 임베딩이 필요한 서비스가 쓰는 창구입니다. 실제 서버 호출은 EMBEDDING_PROVIDER로 선택된 EmbeddingClient가 합니다. */
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingClient embeddingClient;

    // 단일 텍스트 변환
    public PGvector embedText(String text) {
        return embeddingClient.embed(text);
    }

    public List<PGvector> embedTexts(List<String> texts) {
        return embeddingClient.embedBatch(texts);
    }

    /** 선택된 구현체가 임베딩 요청에 쓰는 모델 이름을 반환합니다. */
    public String getModelName() {
        return embeddingClient.getModelName();
    }
}