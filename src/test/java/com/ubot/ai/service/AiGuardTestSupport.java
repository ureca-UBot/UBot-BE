package com.ubot.ai.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 기존 HTTP·DB 통합 테스트의 외부 가드 모의 응답. 실제 판정 연결은 AiGuardIntegrationTest가 검증합니다. */
public final class AiGuardTestSupport {
    private AiGuardTestSupport() {
    }

    public static void allow(AiGuardModelService model) {
        when(model.generate(any())).thenAnswer(call -> {
            LlmRequestDto request = call.getArgument(0);
            String schema = request.generationOptions().schemaName();
            return new LlmResponseDto(switch (schema) {
                case "whole_answer_guard" -> "{\"decision\":\"ALLOW\",\"reason_codes\":[\"SAFE\"],\"explanation\":\"모의 응답\"}";
                case "input_safety_and_planning" -> {
                    var json = JsonMapper.builder().build();
                    String question = json.readTree(request.messages().getLast().content()).get("current_question").asString();
                    yield json.writeValueAsString(Map.of("decision", "ALLOW", "reason", "SAFE", "sub_questions", List.of(question)));
                }
                default -> throw new AssertionError("알 수 없는 가드 요청: " + schema);
            });
        });
    }
}
