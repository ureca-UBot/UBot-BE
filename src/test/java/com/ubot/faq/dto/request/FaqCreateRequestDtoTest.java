package com.ubot.faq.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FaqCreateRequestDtoTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String body = """
            {"categoryId":1,"question":"질문","answer":"답변","intent":"GENERAL"%s}
            """;

    @Test
    void existingJsonWithoutApprovalAndExplicitNullDefaultToFalse() {
        for (String field : new String[]{"", ",\"cacheable\":null", ",\"cacheable\":false"}) {
            assertThat(mapper.readValue(body.formatted(field), FaqCreateRequestDto.class).cacheable()).isFalse();
        }
    }

    @Test
    void explicitApprovalIsPreserved() {
        assertThat(mapper.readValue(body.formatted(",\"cacheable\":true"), FaqCreateRequestDto.class).cacheable()).isTrue();
    }
}
