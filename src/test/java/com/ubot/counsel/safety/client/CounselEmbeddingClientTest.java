package com.ubot.counsel.safety.client;

import static com.ubot.embedding.client.EmbeddingClient.DIMENSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.counsel.common.config.CounselProperties;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

@DisplayName("상담 임베딩 워커 선택")
class CounselEmbeddingClientTest {
	private final CounselProperties properties = new CounselProperties(true, 30, 120, 15, 60, 30,
			new CounselProperties.Guard("http://127.0.0.1:1/unused", "test-model", "test-v1", 0.95, 3, 2, 500, 1000));
	private final ObjectMapper json = new ObjectMapper();
	private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

	@Test
	@DisplayName("워커 모드는 기존 전처리를 유지해 워커로만 요청한다")
	void workerReceivesNormalizedText() {
		var worker = mock(WorkerEmbeddingTransport.class);
		beans.registerSingleton("worker", worker);
		var values = new ArrayList<>(Collections.nCopies(DIMENSIONS, 0.0));
		values.set(0, 1.0);
		when(worker.embed("ABC 요금")).thenReturn(json.valueToTree(values));
		var client = new EmbeddingClient(properties, json, beans.getBeanProvider(WorkerEmbeddingTransport.class), "worker");

		assertThat(client.embed(" ＡＢＣ　 요금 ")).startsWith("[1.0,").endsWith("]");
		verify(worker).embed("ABC 요금");
	}

	@Test
	@DisplayName("워커 구성이 없으면 HTTP로 우회하지 않고 구성 오류를 낸다")
	void missingWorkerFailsAtConstruction() {
		assertThatThrownBy(() -> new EmbeddingClient(properties, json,
				beans.getBeanProvider(WorkerEmbeddingTransport.class), "worker"))
				.isInstanceOf(NoSuchBeanDefinitionException.class);
	}

	@Test
	@DisplayName("워커 실패 시 HTTP를 호출하지 않고 원래 오류를 유지한다")
	void workerFailureDoesNotFallBackToHttp() {
		var worker = mock(WorkerEmbeddingTransport.class);
		beans.registerSingleton("worker", worker);
		when(worker.embed("문의")).thenThrow(new EmbeddingClient.Failure("WORKER_TIMEOUT"));
		var client = new EmbeddingClient(properties, json, beans.getBeanProvider(WorkerEmbeddingTransport.class), "worker");

		assertThatThrownBy(() -> client.embed("문의")).isInstanceOf(EmbeddingClient.Failure.class).hasMessage("WORKER_TIMEOUT");
	}
}
