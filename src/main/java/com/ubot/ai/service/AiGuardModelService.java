package com.ubot.ai.service;

import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 답변 생성 클라이언트의 모델·설정과 분리하고 기존 전송·슬롯·취소 처리를 재사용합니다. */
@Service
public class AiGuardModelService {
    public static final String MODEL = "RedHatAI/Qwen3-4B-Instruct-2507-quantized.w4a16";
    private final LlmService llm;
    private final long permitWaitMillis;

    @Autowired
    public AiGuardModelService(RestClient.Builder builder, MeterRegistry metrics,
            @Value("${AI_GUARD_BASE_URL:${LLM_BASE_URL:http://localhost:8000/v1}}") String baseUrl,
            @Value("${AI_GUARD_CONNECT_TIMEOUT:${LLM_CONNECT_TIMEOUT:3s}}") Duration connectTimeout,
            @Value("${AI_GUARD_READ_TIMEOUT:${LLM_READ_TIMEOUT:120s}}") Duration readTimeout,
            @Value("${AI_GUARD_MAX_CONCURRENT:${LLM_MAX_CONCURRENT:4}}") int maxConcurrent,
            @Value("${AI_GUARD_PERMIT_WAIT_TIMEOUT:5s}") Duration permitWait) {
        this(new LlmService(new LlmClientFactory().createOpenAiCompatible(builder, baseUrl, MODEL,
                connectTimeout, readTimeout), maxConcurrent, metrics), permitWait);
    }

    public AiGuardModelService(LlmService llm, Duration permitWait) {
        if (permitWait == null || !permitWait.isPositive() || permitWait.toMillis() < 1) {
            throw new IllegalArgumentException("AI 가드 호출 슬롯 대기 시간은 1ms 이상이어야 합니다.");
        }
        this.llm = llm;
        this.permitWaitMillis = permitWait.toMillis();
    }

    public LlmResponseDto generate(LlmRequestDto request) {
        return llm.generateJudgment(request, permitWaitMillis);
    }
}
