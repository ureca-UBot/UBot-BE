package com.ubot.ai.dto;

import com.ubot.ai.tool.AiTool;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 
 * intent별 핸들러가 모은 답변 자료입니다. AiService로 넘어간 뒤에는 바뀌지 않습니다. 
 * faqs, sections, tools는 비어 있을 수는 있어도 null이 아닙니다.
 * storeMap만 매장을 조회하지 않았으면 null입니다.
 */
public record AnswerMaterials(
        String question,
        List<FaqSearchResponseDto> faqs,
        List<ContextSection> sections,
        Set<AiTool> tools,
        StoreMapResult storeMap) {

    public AnswerMaterials {
        // Builder를 거치지 않고 만들어도 컬렉션은 null 없이 불변으로 고정합니다.
        faqs = faqs == null ? List.of() : List.copyOf(faqs);
        sections = sections == null ? List.of() : List.copyOf(sections);
        tools = tools == null || tools.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(tools));
    }
    
    public static Builder builder(String question) {
        return new Builder(question);
    }

    /** 요청마다 하나 만들어 모든 핸들러에 넘기고, 다 채운 뒤 build()로 고정합니다. */
    public static final class Builder {

        private final String question;
        private final List<FaqSearchResponseDto> faqs = new ArrayList<>();
        private final List<ContextSection> sections = new ArrayList<>();
        private final Set<AiTool> tools = EnumSet.noneOf(AiTool.class);
        private StoreMapResult storeMap;

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
        
        public Builder storeMap(StoreMapResult value) {
            storeMap = value;
            return this;
        }

        public AnswerMaterials build() {
        	// 복사와 불변 처리는 생성자가 맡습니다.
            return new AnswerMaterials(question, faqs, sections, tools, storeMap);
        }
    }
}
