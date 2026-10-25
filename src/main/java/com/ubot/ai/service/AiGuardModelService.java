package com.ubot.ai.service;

import com.ubot.ai.config.AiEngine;
import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** 실행 환경의 모델 연결을 기본으로 쓰고, 명시적인 가드 설정만 별도로 적용합니다. */
@Service
public class AiGuardModelService {
    private final LlmService llm;
    private final LlmClient guardClient;
    private final long permitWaitMillis;

    @Autowired
    public AiGuardModelService(LlmService chatLlm, AiRuntimeProperties runtime, Environment environment,
            RestClient.Builder builder, MeterRegistry metrics,
            @Value("${AI_GUARD_CONNECT_TIMEOUT:${LLM_CONNECT_TIMEOUT:3s}}") Duration connectTimeout,
            @Value("${AI_GUARD_READ_TIMEOUT:${LLM_READ_TIMEOUT:120s}}") Duration readTimeout,
            @Value("${AI_GUARD_MAX_CONCURRENT:${LLM_MAX_CONCURRENT:4}}") int maxConcurrent,
            @Value("${AI_GUARD_PERMIT_WAIT_TIMEOUT:5s}") Duration permitWait) {
        this(connection(chatLlm, runtime, environment, builder, metrics, connectTimeout, readTimeout,
                maxConcurrent), permitWait);
    }

    private static Connection connection(LlmService chatLlm, AiRuntimeProperties runtime, Environment environment,
            RestClient.Builder builder, MeterRegistry metrics, Duration connectTimeout, Duration readTimeout,
            int maxConcurrent) {
        String configuredEngine = environment.getProperty("AI_GUARD_ENGINE");
        AiEngine engine = StringUtils.hasText(configuredEngine)
                ? AiEngine.from(configuredEngine) : runtime.resolvedChatEngine();
        String defaultModel = engine == AiEngine.OLLAMA
                ? environment.getProperty("spring.ai.ollama.chat.options.model", environment.getProperty("OLLAMA_CHAT_MODEL", ""))
                : environment.getProperty("LLM_MODEL", "ubot-chat");
        String baseUrl = override(environment, "AI_GUARD_BASE_URL", baseUrl(environment, engine));
        String model = override(environment, "AI_GUARD_MODEL", defaultModel);
        boolean sharedServer = ServerAddress.of(baseUrl)
                .equals(ServerAddress.of(baseUrl(environment, runtime.resolvedChatEngine())));
        // 별도 연결 설정이 없으면 전송 클라이언트까지 기존 답변 생성 인스턴스를 그대로 사용합니다.
        if (sharedServer && !hasClientOverrides(environment)) {
            return new Connection(chatLlm, null);
        }
        var factory = new LlmClientFactory();
        LlmClient client = switch (engine) {
            case OLLAMA -> factory.createOllama(builder, baseUrl, model, connectTimeout, readTimeout);
            case VLLM -> factory.createOpenAiCompatible(builder, baseUrl, model, connectTimeout, readTimeout);
        };
        // 모델명·프로토콜·API 경로가 달라도 같은 서버라면 기존 허가 안에서 호출합니다.
        return sharedServer ? new Connection(chatLlm, client)
                : new Connection(new LlmService(client, maxConcurrent, metrics, LlmService.MetricsClient.GUARD), null);
    }

    private static String baseUrl(Environment environment, AiEngine engine) {
        return engine == AiEngine.OLLAMA
                ? environment.getProperty("ollama.base-url", environment.getProperty("OLLAMA_BASE_URL", "http://localhost:11435"))
                : environment.getProperty("LLM_BASE_URL", "http://localhost:8000/v1");
    }

    private static boolean hasClientOverrides(Environment environment) {
        return StringUtils.hasText(environment.getProperty("AI_GUARD_ENGINE"))
                || StringUtils.hasText(environment.getProperty("AI_GUARD_BASE_URL"))
                || StringUtils.hasText(environment.getProperty("AI_GUARD_MODEL"))
                || StringUtils.hasText(environment.getProperty("AI_GUARD_CONNECT_TIMEOUT"))
                || StringUtils.hasText(environment.getProperty("AI_GUARD_READ_TIMEOUT"));
    }

    private static String override(Environment environment, String name, String fallback) {
        String value = environment.getProperty(name);
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    public AiGuardModelService(LlmService llm, Duration permitWait) {
        this(new Connection(llm, null), permitWait);
    }

    private AiGuardModelService(Connection connection, Duration permitWait) {
        if (permitWait == null || !permitWait.isPositive() || permitWait.toMillis() < 1) {
            throw new IllegalArgumentException("AI 가드 호출 슬롯 대기 시간은 1ms 이상이어야 합니다.");
        }
        this.llm = connection.llm();
        this.guardClient = connection.client();
        this.permitWaitMillis = permitWait.toMillis();
    }

    public LlmResponseDto generate(LlmRequestDto request) {
        return guardClient == null ? llm.generateGuard(request, permitWaitMillis)
                : llm.generateGuard(request, permitWaitMillis, () -> guardClient.generateAnswer(request));
    }

    private record Connection(LlmService llm, LlmClient client) { }

    /** 경로·모델명으로 같은 서버의 호출 상한을 나누지 않습니다. DNS 조회는 하지 않습니다. */
    private record ServerAddress(String scheme, String host, int port) {
        private static ServerAddress of(String baseUrl) {
            URI uri = URI.create(baseUrl.strip());
            if (uri.getHost() == null || uri.getScheme() == null) {
                throw new IllegalArgumentException("LLM 서버의 절대 HTTP 주소가 필요합니다.");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")) {
                host = "loopback";
            }
            int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
            return new ServerAddress(scheme, host, port);
        }
    }
}
