package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class AiGuardModelServiceTest {
    @Test
    void 기존_전송의_제한시간과_취소_처리를_사용하는_판정_호출에_위임한다() {
        var llm = mock(LlmService.class);
        var request = new LlmRequestDto(List.of());
        var result = new LlmResponseDto("모의 판정");
        when(llm.generateJudgment(request, 5000)).thenReturn(result);
        var guard = new AiGuardModelService(llm, Duration.ofSeconds(5));
        assertThat(guard.generate(request)).isSameAs(result);
        verify(llm).generateJudgment(request, 5000);
    }

    @Test
    void 슬롯_대기_실패를_허용으로_바꾸지_않는다() {
        var llm = mock(LlmService.class);
        var request = new LlmRequestDto(List.of());
        when(llm.generateJudgment(request, 5)).thenThrow(new LlmException(LlmErrorCode.LLM_BUSY));
        assertThatThrownBy(() -> new AiGuardModelService(llm, Duration.ofMillis(5)).generate(request))
                .isInstanceOfSatisfying(LlmException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_BUSY));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void 무제한_슬롯_대기를_허용하지_않는다(long millis) {
        assertThatThrownBy(() -> new AiGuardModelService(mock(LlmService.class), Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 선택한_실험_프롬프트와_스키마의_해시를_유지한다() throws Exception {
        var prefix = "prompts/ai-guard/";
        var manifest = JsonMapper.builder().build().readTree(
                new ClassPathResource(prefix + "provenance.json").getContentAsString(StandardCharsets.UTF_8));
        // 실험 체크포인트 기록은 보존하며, 실제 API 요청 모델명은 환경 설정을 따릅니다.
        assertThat(manifest.get("model").asString())
                .isEqualTo("RedHatAI/Qwen3-4B-Instruct-2507-quantized.w4a16");
        var resources = manifest.get("resources");
        assertThat(resources.size()).isEqualTo(4);
        for (String file : List.of("input-integrated-system.txt", "input-integrated-schema.json",
                "output-safety-system.txt", "output-safety-schema.json")) {
            byte[] bytes = new ClassPathResource(prefix + file).getContentAsByteArray();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .as(file).isEqualTo(resources.get(file).get("sha256").asString());
        }
    }
}
