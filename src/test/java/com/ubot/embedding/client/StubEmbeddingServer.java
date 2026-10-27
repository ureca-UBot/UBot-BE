package com.ubot.embedding.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 준비한 응답을 차례로 돌려주고 받은 요청 본문을 기록하는 테스트용 임베딩 서버입니다. */
final class StubEmbeddingServer implements AutoCloseable {

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private final Queue<StubResponse> responses = new ConcurrentLinkedQueue<>();
	private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
	private final CountDownLatch released = new CountDownLatch(1);
	private final HttpServer server;

	StubEmbeddingServer(String embeddingPath) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(embeddingPath, this::handle);
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	void respond(String body) {
		respond(200, body);
	}

	void respond(int status, String body) {
		responses.add(new StubResponse(status, body, false));
	}

	/** 다음 요청에는 서버를 닫을 때까지 응답하지 않습니다. */
	void hang() {
		responses.add(new StubResponse(0, "", true));
	}

	List<JsonNode> requests() {
		return requests;
	}

	@Override
	public void close() {
		released.countDown();
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		requests.add(JSON_MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
		StubResponse response = responses.poll();
		if (response == null) {
			// 준비한 응답보다 많이 호출하면 테스트가 실패하게 합니다.
			response = new StubResponse(500, "{\"error\":\"unexpected call\"}", false);
		}
		if (response.hang()) {
			try {
				released.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
			return;
		}
		byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(response.status(), bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private record StubResponse(int status, String body, boolean hang) {
	}
}
