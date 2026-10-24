package com.ubot.prompt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.client.OpenAiCompatibleLlmClient;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import com.ubot.prompt.format.AiOutputGuardAssessment;
import com.ubot.prompt.format.AiOutputGuardAssessment.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 실제 리소스·전송 직렬화와 파서만 검증하며 외부 네트워크를 사용하지 않습니다. */
class AiGuardOutputContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODEL = "RedHatAI/Qwen3-4B-Instruct-2507-quantized.w4a16";
    private static final String ANSWER = "로밍은 앱에서 신청할 수 있습니다.";

    @Test
    void 빈_목록_구조만_사용하고_기존_출력한도는_유지한다() {
        var request = request();
        assertThat(request.generationOptions().maxTokens()).isEqualTo(1024);
        assertThat(request.generationOptions().schemaName()).isEqualTo("whole_answer_guard");
        assertThat(request.messages().getFirst().content()).contains("findings 배열 하나", "certainty, type, answer_quote")
                .doesNotContain("output-checks-v2", "9개 키가 모두 있어야 합니다");
        var schema = JSON.valueToTree(request.responseSchema());
        assertThat(schema.get("properties").propertyNames()).containsExactly("findings");
        assertThat(schema.get("properties").get("findings").get("maxItems").asInt()).isEqualTo(4);
        var input = JSON.readTree(request.messages().getLast().content());
        assertThat(input.get("candidate_answer").asString()).isEqualTo(ANSWER);
        assertThat(input.get("evidence_ids").get(0).asString()).isEqualTo("9999");
    }

    @Test
    void 명시한_출력한도도_새_응답_구조와_함께_유지한다() {
        var words = new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)).preview(Set.of());
        var request = new AiGuardPromptService(words, 2048).output(ANSWER, List.of(),
                AnswerMaterials.builder("로밍 신청 방법을 알려 주세요.").build(), null);
        assertThat(request.generationOptions().maxTokens()).isEqualTo(2048);
        assertThat(request.generationOptions().schemaName()).isEqualTo("whole_answer_guard");
        assertThat(JSON.valueToTree(request.responseSchema()).get("properties").propertyNames())
                .containsExactly("findings");
    }

    @Test
    void 스키마와_파서는_판정_사유_조합을_독립적으로_받지_않는다() {
        var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JSON.valueToTree(request().responseSchema()));
        assertThat(validator.validate(JSON.readTree("{\"findings\":[]}"))).isEmpty();
        for (String certainty : List.of("CONFIRMED", "UNCERTAIN")) {
            for (Type type : Type.values()) {
                var value = JSON.valueToTree(Map.of("findings", List.of(Map.of("certainty", certainty,
                        "type", type.name(), "answer_quote", ANSWER))));
                boolean valid = !certainty.equals("CONFIRMED") || type != Type.INSUFFICIENT_EVIDENCE;
                assertThat(validator.validate(value).isEmpty()).as("%s/%s", certainty, type).isEqualTo(valid);
                if (valid) {
                    assertThat(AiOutputGuardAssessment.parse(JSON.writeValueAsString(value), ANSWER).decision())
                            .isEqualTo(certainty.equals("CONFIRMED") ? Decision.BLOCK : Decision.REVIEW);
                }
            }
        }
        assertThat(validator.validate(JSON.readTree("{\"decision\":\"ALLOW\",\"reason_codes\":[\"FALSE_EXECUTION\"],\"explanation\":\"정상\"}")))
                .isNotEmpty();
    }

    @Test
    void 잘린_응답은_유효_JSON이어도_허용이나_재시도로_바꾸지_않는다() {
        var request = request();
        var builder = RestClient.builder().baseUrl("http://offline.invalid/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://offline.invalid/v1/chat/completions")).andExpect(http -> {
            var body = JSON.readTree(((MockClientHttpRequest) http).getBodyAsString());
            assertThat(body.get("model").asString()).isEqualTo(MODEL);
            assertThat(body.get("max_tokens").asInt()).isEqualTo(1024);
            assertThat(body.get("response_format").get("json_schema").get("schema"))
                    .isEqualTo(JSON.valueToTree(request.responseSchema()));
        }).andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}]}", MediaType.APPLICATION_JSON));
        var client = new OpenAiCompatibleLlmClient(builder.build(), MODEL);
        assertThatThrownBy(() -> client.generateAnswer(request)).isInstanceOfSatisfying(LlmException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
        server.verify();
    }

    private com.ubot.llm.dto.request.LlmRequestDto request() {
        var words = new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)).preview(Set.of());
        return new AiGuardPromptService(words).output(ANSWER, List.of("9999"),
                AnswerMaterials.builder("로밍 신청 방법을 알려 주세요.").build(), null);
    }
}
