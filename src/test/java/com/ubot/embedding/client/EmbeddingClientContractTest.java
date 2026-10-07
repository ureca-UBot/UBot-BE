package com.ubot.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgvector.PGvector;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EmbeddingClient 구현체가 UBot 관점에서 같은 동작을 하는지 확인하는 공통 계약입니다.
 * 구현체별 테스트는 서버 경로와 응답 형식만 채우고, 검증은 여기 있는 테스트를 그대로 물려받습니다.
 */
abstract class EmbeddingClientContractTest {

	protected static final String MODEL_NAME = "contract-test-embedding";

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private StubEmbeddingServer server;

	/** 구현체가 호출하는 임베딩 API 경로입니다. */
	protected abstract String embeddingPath();

	/** serverUrl은 테스트 서버 주소(http://127.0.0.1:포트)입니다. */
	protected abstract EmbeddingClient createClient(String serverUrl, String modelName, Duration readTimeout);

	/** 벡터가 입력 순서대로 담긴 정상 응답 본문입니다. */
	protected abstract String embeddingResponse(List<List<Object>> vectors);

	/** 벡터가 하나도 없는 응답 본문입니다. */
	protected abstract String emptyResponse();

	protected static String toJson(Object value) {
		return JSON_MAPPER.writeValueAsString(value);
	}

	/** 앞쪽 값만 지정하고 나머지는 0으로 채운 1024차원 벡터입니다. */
	protected static List<Object> vector(double... head) {
		List<Object> values = new ArrayList<>(Collections.nCopies(EmbeddingClient.DIMENSIONS, 0.0));
		for (int index = 0; index < head.length; index++) {
			values.set(index, head[index]);
		}
		return values;
	}

	protected StubEmbeddingServer server() {
		return server;
	}

	protected EmbeddingClient client() {
		return createClient(server.url(), MODEL_NAME, Duration.ofSeconds(3));
	}

	@BeforeEach
	void startServer() throws IOException {
		server = new StubEmbeddingServer(embeddingPath());
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void embedsSingleText() {
		server.respond(embeddingResponse(List.of(vector(0.1, 0.2, 0.3))));

		PGvector result = client().embed("유심 재발급 어떻게 해요");

		assertThat(result.toArray()).hasSize(EmbeddingClient.DIMENSIONS).startsWith(0.1f, 0.2f, 0.3f);
		assertThat(server.requests()).hasSize(1);
	}

	@Test
	void embedsBatchInInputOrder() {
		server.respond(embeddingResponse(List.of(vector(0.1), vector(0.2), vector(0.3))));

		List<PGvector> results = client().embedBatch(List.of("질문1", "질문2", "질문3"));

		assertThat(results).hasSize(3);
		assertThat(results).extracting(result -> result.toArray()[0]).containsExactly(0.1f, 0.2f, 0.3f);
		// 여러 문장을 한 번의 요청으로 보냅니다.
		assertThat(server.requests()).hasSize(1);
	}

	@Test
	void sendsConfiguredModelNameAndInputs() {
		server.respond(embeddingResponse(List.of(vector(0.1), vector(0.2))));
		EmbeddingClient client = client();

		client.embedBatch(List.of("질문1", "질문2"));

		JsonNode request = server.requests().getFirst();
		// 시도 기록에 남기는 이름이 실제 요청에 넣은 모델 이름과 같아야 합니다.
		assertThat(client.getModelName()).isEqualTo(MODEL_NAME);
		assertThat(request.get("model").asString()).isEqualTo(MODEL_NAME);
		assertThat(request.get("input")).extracting(JsonNode::asString).containsExactly("질문1", "질문2");
	}

	@ParameterizedTest
	@ValueSource(ints = {3, 1023, 1025})
	void rejectsVectorWithWrongDimension(int dimension) {
		server.respond(embeddingResponse(List.of(new ArrayList<>(Collections.nCopies(dimension, 0.1)))));

		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	@Test
	void rejectsValueThatIsNotFinite() {
		// float 범위를 넘는 값은 DB에 넣을 수 없는 Infinity가 됩니다.
		server.respond(embeddingResponse(List.of(vector(0.1, 1e39))));

		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	@Test
	void rejectsValueThatIsNotNumber() {
		List<Object> withNull = vector(0.1);
		withNull.set(1, null);
		List<Object> withText = vector(0.1);
		withText.set(1, "NaN");
		server.respond(embeddingResponse(List.of(withNull)));
		server.respond(embeddingResponse(List.of(withText)));

		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	@Test
	void rejectsFewerVectorsThanInputs() {
		server.respond(embeddingResponse(List.of(vector(0.1))));

		assertFailure(() -> client().embedBatch(List.of("질문1", "질문2")),
				EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	@Test
	void rejectsResponseWithoutVectors() {
		server.respond(emptyResponse());
		server.respond("{}");

		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_RESPONSE_INVALID);
	}

	@Test
	void mapsUnreadableBodyToServiceUnavailable() {
		// JSON으로 읽을 수 없는 본문은 기존 구현과 같이 서버 장애로 봅니다.
		server.respond("not-json");

		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 500, 503})
	void mapsErrorStatusToServiceUnavailable(int status) {
		server.respond(status, "{\"error\":\"failed\"}");

		assertFailure(() -> client().embed("질문"), EmbeddingErrorCode.EMBEDDING_SERVICE_UNAVAILABLE);
		// 자동으로 다시 요청하지 않습니다.
		assertThat(server.requests()).hasSize(1);
	}

	@Test
	void mapsReadTimeout() {
		server.hang();
		EmbeddingClient client = createClient(server.url(), MODEL_NAME, Duration.ofMillis(150));

		assertFailure(() -> client.embed("질문"), EmbeddingErrorCode.EMBEDDING_TIMEOUT);
	}

	@Test
	void mapsRefusedConnectionToTimeout() {
		// 연결 자체가 안 되는 경우도 기존 구현과 같이 EMBEDDING_TIMEOUT으로 알립니다.
		EmbeddingClient client = createClient("http://127.0.0.1:1", MODEL_NAME, Duration.ofSeconds(3));

		assertFailure(() -> client.embed("질문"), EmbeddingErrorCode.EMBEDDING_TIMEOUT);
	}

	protected void assertFailure(ThrowingCallable call, EmbeddingErrorCode expected) {
		assertThatThrownBy(call)
				.isInstanceOfSatisfying(EmbeddingException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
	}
}
