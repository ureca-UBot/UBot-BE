package com.ubot.embedding.client;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 임베딩 서버 호출 시간과 결과를 embedding.requests 지표로 남깁니다.
 * 채팅 검색·백필·상담 임베딩이 모두 이 빈을 거치므로 호출 경로마다 따로 재지 않습니다.
 */
public final class MeteredEmbeddingClient implements EmbeddingClient {

	private final EmbeddingClient delegate;
	private final MeterRegistry metrics;

	public MeteredEmbeddingClient(EmbeddingClient delegate, MeterRegistry metrics) {
		this.delegate = delegate;
		this.metrics = metrics;
	}

	@Override
	public PGvector embed(String text) {
		return record("single", () -> delegate.embed(text));
	}

	@Override
	public List<PGvector> embedBatch(List<String> texts) {
		return record("batch", () -> delegate.embedBatch(texts));
	}

	@Override
	public String getModelName() {
		return delegate.getModelName();
	}

	/** AI_MODE로 선택된 실제 구현체입니다. */
	public EmbeddingClient delegate() {
		return delegate;
	}

	private <T> T record(String operation, Supplier<T> call) {
		long startedAt = System.nanoTime();
		String outcome = "error";
		try {
			T result = call.get();
			outcome = "success";
			return result;
		} catch (EmbeddingException exception) {
			outcome = outcomeOf(exception);
			throw exception;
		} finally {
			metrics.timer("embedding.requests", "operation", operation, "outcome", outcome)
					.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
		}
	}

	private static String outcomeOf(EmbeddingException exception) {
		if (exception.getErrorCode() == EmbeddingErrorCode.EMBEDDING_TIMEOUT) return "timeout";
		if (exception.getErrorCode() == EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE) return "unavailable";
		if (exception.getErrorCode() == EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID) return "invalid_response";
		return "error";
	}
}
