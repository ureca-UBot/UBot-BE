package com.ubot.embedding.client;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import java.util.ArrayList;
import java.util.List;

/** 임베딩 서버가 돌려준 벡터를 DB에 저장할 수 있는 값인지 확인하고 PGvector로 바꿉니다. 두 구현체가 같은 기준을 씁니다. */
final class EmbeddingVectors {

	private EmbeddingVectors() {
	}

	/** 개수·차원이 다르거나 숫자가 아닌 값, 유한하지 않은 값이 있으면 EMBEDDING_RESPONSE_INVALID로 거절합니다. */
	static List<PGvector> toPGvectors(List<?> rawVectors, int expectedCount) {
		if (rawVectors.size() != expectedCount) {
			throw invalidResponse();
		}
		List<PGvector> vectors = new ArrayList<>(rawVectors.size());
		for (Object rawVector : rawVectors) {
			vectors.add(toPGvector(rawVector));
		}
		return vectors;
	}

	static EmbeddingException invalidResponse() {
		return new EmbeddingException(EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	private static PGvector toPGvector(Object rawVector) {
		if (!(rawVector instanceof List<?> values) || values.size() != EmbeddingClient.DIMENSIONS) {
			throw invalidResponse();
		}
		float[] result = new float[values.size()];
		for (int index = 0; index < result.length; index++) {
			if (!(values.get(index) instanceof Number number)) {
				throw invalidResponse();
			}
			// float 범위를 넘는 값은 여기서 Infinity가 되므로 변환한 뒤에 확인합니다.
			result[index] = number.floatValue();
			if (!Float.isFinite(result[index])) {
				throw invalidResponse();
			}
		}
		return new PGvector(result);
	}
}
