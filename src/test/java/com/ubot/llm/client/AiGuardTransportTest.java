package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.ubot.ai.service.AiGuardModelService;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 메모리 전송 모의 객체를 쓰며 소켓·실제 모델 서버에 접속하지 않습니다. */
class AiGuardTransportTest {
    @Test
    void 가드만_토큰한도와_스키마이름을_지정하고_기존_답변_요청은_그대로다() {
        var builder = RestClient.builder().baseUrl("http://guard.invalid/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OpenAiCompatibleLlmClient(builder.build(), AiGuardModelService.MODEL);
        server.expect(requestTo("http://guard.invalid/v1/chat/completions")).andExpect(req -> {
            var body = JsonMapper.builder().build().readTree(((MockClientHttpRequest) req).getBodyAsString());
            assertThat(body.get("model").asString()).isEqualTo(AiGuardModelService.MODEL);
            assertThat(body.get("max_tokens").asInt()).isEqualTo(512);
            assertThat(body.get("temperature").asDouble()).isZero();
            assertThat(body.get("response_format").get("json_schema").get("name").asString()).isEqualTo("input_safety_and_planning");
        }).andRespond(withSuccess(reply(), MediaType.APPLICATION_JSON));
        client.generateAnswer(request().withGenerationOptions("input_safety_and_planning", 512, true));
        server.verify(); server.reset();
        server.expect(requestTo("http://guard.invalid/v1/chat/completions")).andExpect(req -> {
            var body = JsonMapper.builder().build().readTree(((MockClientHttpRequest) req).getBodyAsString());
            assertThat(body.has("max_tokens")).isFalse();
            assertThat(body.get("response_format").get("json_schema").get("name").asString()).isEqualTo("faq_answer");
        }).andRespond(withSuccess(reply(), MediaType.APPLICATION_JSON));
        client.generateAnswer(request()); server.verify();
    }

    @Test
    void 가드_서버_오류_본문은_로그나_예외_원인으로_유출하지_않는다() {
        var builder = RestClient.builder().baseUrl("http://guard.invalid/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OpenAiCompatibleLlmClient(builder.build(), AiGuardModelService.MODEL);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            server.expect(requestTo("http://guard.invalid/v1/chat/completions"))
                    .andRespond(withServerError().body("PRIVATE_QUESTION_OR_MODEL_DATA"));
            assertThatThrownBy(() -> client.generateAnswer(request().withGenerationOptions("input_safety_and_planning", 512, true)))
                    .isInstanceOfSatisfying(LlmException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
                        assertThat(e.getCause()).isNull();
                    });
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("PRIVATE_QUESTION_OR_MODEL_DATA"));
            server.verify();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    private LlmRequestDto request() {
        return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "검사 자료")))
                .withResponseSchema(Map.of("type", "object"));
    }

    private String reply() {
        return "{\"model\":\""+AiGuardModelService.MODEL+"\",\"choices\":[{\"finish_reason\":\"stop\","
                +"\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
    }
}
