package com.ubot.embedding.config;

import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.client.OllamaEmbeddingClient;
import com.ubot.embedding.client.OpenAiCompatibleEmbeddingClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * EMBEDDING_PROVIDER(ollama 또는 openai-compatible)에 따라 EmbeddingClient 구현체 하나만 빈으로 등록합니다.
 * 값이 없으면 Ollama를 씁니다.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

	// spring.ai.ollama와는 별개인 ollama.* 설정을 읽습니다. 기존 EmbeddingService가 읽던 값과 같습니다.
	@Bean
	@ConditionalOnProperty(name = "EMBEDDING_PROVIDER", havingValue = "ollama", matchIfMissing = true)
	public EmbeddingClient ollamaEmbeddingClient(
			RestClient.Builder restClientBuilder,
			@Value("${ollama.base-url}") String baseUrl,
			@Value("${ollama.embedding.model}") String modelName,
			@Value("${ollama.connect-timeout:3s}") Duration connectTimeout,
			@Value("${ollama.read-timeout:10s}") Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);

		// 다른 HTTP 클라이언트가 쓰는 공용 빌더는 바꾸지 않습니다.
		RestClient restClient = restClientBuilder.clone()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.build();
		return new OllamaEmbeddingClient(restClient, modelName);
	}

	@Bean
	@ConditionalOnProperty(name = "EMBEDDING_PROVIDER", havingValue = "openai-compatible")
	public EmbeddingClient openAiCompatibleEmbeddingClient(
			RestClient.Builder restClientBuilder,
			@Value("${EMBEDDING_BASE_URL:http://localhost:8001/v1}") String baseUrl,
			@Value("${EMBEDDING_MODEL:ubot-embedding}") String modelName,
			@Value("${EMBEDDING_CONNECT_TIMEOUT:3s}") Duration connectTimeout,
			@Value("${EMBEDDING_READ_TIMEOUT:30s}") Duration readTimeout) {
		Assert.isTrue(connectTimeout.isPositive(), "임베딩 연결 제한 시간은 0보다 커야 합니다.");
		Assert.isTrue(readTimeout.isPositive(), "임베딩 응답 제한 시간은 0보다 커야 합니다.");

		// vLLM 서버(uvicorn)는 HTTP/2 업그레이드 요청을 지원하지 않으므로 HTTP/1.1로 고정합니다.
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(connectTimeout)
				.build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);

		RestClient restClient = restClientBuilder.clone()
				.baseUrl(baseUrl.replaceAll("/+$", ""))
				.requestFactory(requestFactory)
				.build();
		return new OpenAiCompatibleEmbeddingClient(restClient, modelName);
	}
}
