package com.ubot.guardreview.service;

import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.client.LlmClient;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 관리자 분석만 전용 모델로 보냅니다. 상담 및 실시간 가드의 공통 모델 선택은 변경하지 않습니다. */
@Component
public class ReviewModelClient {

	public static final String MODEL = "Qwen/Qwen3-14B-AWQ";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
	};
	private final RestClient.Builder builder;
	private final String baseUrl;
	private final Duration connectTimeout;
	private final Duration readTimeout;
	private final int maxTokens;
	private volatile LlmClient service;

	public ReviewModelClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
			Duration readTimeout) {
		this(builder, baseUrl, connectTimeout, readTimeout, 16384);
	}

	@Autowired
	public ReviewModelClient(RestClient.Builder builder,
			@Value("${app.guard-review.llm.base-url:${GUARD_REVIEW_LLM_BASE_URL:}}") String baseUrl,
			@Value("${app.guard-review.llm.connect-timeout:${GUARD_REVIEW_LLM_CONNECT_TIMEOUT:3s}}") Duration connectTimeout,
			@Value("${app.guard-review.llm.read-timeout:${GUARD_REVIEW_LLM_READ_TIMEOUT:180s}}") Duration readTimeout,
			@Value("${app.guard-review.llm.max-tokens:${GUARD_REVIEW_LLM_MAX_TOKENS:16384}}") int maxTokens) {
		Assert.notNull(builder, "관리자 분석용 HTTP 빌더가 필요합니다.");
		Assert.isTrue(connectTimeout != null && connectTimeout.isPositive(), "연결 제한 시간은 0보다 커야 합니다.");
		Assert.isTrue(readTimeout != null && readTimeout.isPositive(), "응답 제한 시간은 0보다 커야 합니다.");
		Assert.isTrue(maxTokens > 0, "출력 토큰 상한은 0보다 커야 합니다.");
		this.builder = builder.clone();
		this.baseUrl = baseUrl;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.maxTokens = maxTokens;
	}

	public LlmResponseDto generateJudgment(LlmRequestDto request) {
		if (request != null && request.hasTools()) {
			throw new IllegalArgumentException("관리자 위험 표현 분석에서는 도구를 호출할 수 없습니다.");
		}
		return service().generateAnswer(request);
	}

	private LlmClient service() {
		LlmClient current = service;
		if (current != null)
			return current;
		synchronized (this) {
			if (service == null) {
				validateEndpoint();
				// 공통 클라이언트의 오류 변환·추론 텍스트 제거·응답 검사를 재사용합니다.
				var configured = builder.clone()
						.requestInterceptors(interceptors -> interceptors.addFirst((request, body, execution) -> {
							Map<String, Object> payload = JSON.readValue(body, OBJECT);
							payload.put("chat_template_kwargs", Map.of("enable_thinking", true));
							payload.put("max_tokens", maxTokens);
							payload.put("temperature", 0.6);
							payload.put("top_p", 0.95);
							payload.put("top_k", 20);
							payload.put("min_p", 0);
							byte[] encoded = JSON.writeValueAsBytes(payload);
							request.getHeaders().set(HttpHeaders.CONTENT_LENGTH, String.valueOf(encoded.length));
							return execution.execute(request, encoded);
						}));
				var client = new LlmClientFactory().createOpenAiCompatible(configured, baseUrl.strip(), MODEL,
						connectTimeout, readTimeout);
				service = client;
			}
			return service;
		}
	}

	private void validateEndpoint() {
		if (!StringUtils.hasText(baseUrl)) {
			throw new IllegalStateException("관리자 분석용 Qwen3-14B-AWQ 연결 주소가 설정되지 않았습니다.");
		}
		URI uri = URI.create(baseUrl.strip());
		if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || !StringUtils.hasText(uri.getHost())
				|| uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
			throw new IllegalStateException("관리자 분석용 HTTP(S) API 주소를 확인해 주세요.");
		}
	}
}
