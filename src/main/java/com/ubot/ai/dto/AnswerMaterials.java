package com.ubot.ai.dto;

import com.ubot.ai.tool.AiTool;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** intent별 핸들러가 모은 답변 자료입니다. AiService로 넘어간 뒤에는 바뀌지 않습니다. location은 null일 수 있습니다. */
public record AnswerMaterials(
        String question,
        List<FaqSearchResponseDto> faqs,
        List<ContextSection> sections,
        Set<AiTool> tools,
        Location location) {

    public static Builder builder(String question) {
        return new Builder(question);
    }

    /** 요청마다 하나 만들어 모든 핸들러에 넘기고, 다 채운 뒤 build()로 고정합니다. */
    public static final class Builder {

        private final String question;
        private final List<FaqSearchResponseDto> faqs = new ArrayList<>();
        private final List<ContextSection> sections = new ArrayList<>();
        private final Set<AiTool> tools = EnumSet.noneOf(AiTool.class);
        private Location location;

        private Builder(String question) {
            this.question = question;
        }

        public Builder addFaqs(List<FaqSearchResponseDto> values) {
            faqs.addAll(values);
            return this;
        }

        public Builder addSection(ContextSection section) {
            sections.add(section);
            return this;
        }

        public Builder enableTool(AiTool tool) {
            tools.add(tool);
            return this;
        }

        public Builder location(Location value) {
            location = value;
            return this;
        }

        public AnswerMaterials build() {
            return new AnswerMaterials(question, List.copyOf(faqs), List.copyOf(sections),
                    Collections.unmodifiableSet(EnumSet.copyOf(tools)), location);
        }
    }
}
