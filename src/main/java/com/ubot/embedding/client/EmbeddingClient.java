package com.ubot.embedding.client;

import com.pgvector.PGvector;
import java.util.List;

/** 임베딩 서버와의 통신을 맡습니다. 구현체는 EMBEDDING_PROVIDER에 따라 하나만 빈으로 등록됩니다. */
public interface EmbeddingClient {

	/** DB 벡터 컬럼(vector(1024))과 같은 차원입니다. 서버가 다른 차원을 돌려주면 실패로 처리합니다. */
	int DIMENSIONS = 1024;

	default PGvector embed(String text) {
		return embedBatch(List.of(text)).get(0);
	}

	/** 입력과 같은 순서, 같은 개수의 벡터를 반환합니다. */
	List<PGvector> embedBatch(List<String> texts);

	/** 임베딩 요청에 넣는 모델 이름입니다. 답변 시도 기록(embedding_model)에 남길 때 씁니다. */
	String getModelName();
}
