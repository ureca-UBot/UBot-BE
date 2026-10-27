package com.ubot.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("임베딩 호출 지표 테스트")
class MeteredEmbeddingClientTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final List<RuntimeException> failures = new ArrayList<>();
	private final EmbeddingClient delegate = new EmbeddingClient() {
		@Override
		public List<PGvector> embedBatch(List<String> texts) {
			if (!failures.isEmpty()) throw failures.removeFirst();
			return texts.stream().map(text -> new PGvector(new float[] {1f})).toList();
		}

		@Override
		public String getModelName() {
			return "test-model";
		}
	};
	private final MeteredEmbeddingClient client = new MeteredEmbeddingClient(delegate, registry);

	@Test
	@DisplayName("단건·묶음 호출을 결과별로 나눠 한 번씩 센다")
	void recordsEachCallOnceByOperationAndOutcome() {
		client.embed("질문");
		client.embedBatch(List.of("질문1", "질문2"));
		failures.add(new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT));
		assertThatThrownBy(() -> client.embed("느린 질문")).isInstanceOf(EmbeddingException.class);
		failures.add(new IllegalStateException("예상 못 한 오류"));
		assertThatThrownBy(() -> client.embedBatch(List.of("질문"))).isInstanceOf(IllegalStateException.class);

		assertThat(calls("single", "success")).isEqualTo(1);
		assertThat(calls("batch", "success")).isEqualTo(1);
		assertThat(calls("single", "timeout")).isEqualTo(1);
		assertThat(calls("batch", "error")).isEqualTo(1);
	}

	@Test
	@DisplayName("모델 이름과 실제 구현체는 감싼 클라이언트의 것을 그대로 돌려준다")
	void exposesDelegate() {
		assertThat(client.getModelName()).isEqualTo("test-model");
		assertThat(client.delegate()).isSameAs(delegate);
	}

	private double calls(String operation, String outcome) {
		return registry.get("embedding.requests").tag("operation", operation).tag("outcome", outcome).timer().count();
	}
}
