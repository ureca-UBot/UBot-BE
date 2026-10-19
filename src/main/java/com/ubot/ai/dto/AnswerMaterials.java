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
 * faqs, sections, tools, subQuestions, history는 비어 있을 수는 있어도 null이 아닙니다.
 * storeMap은 매장을 조회하지 않았으면 null이고, userId는 비회원이면 null입니다.
 * subQuestions는 하위 질문마다 faqs 중 그 질문으로 찾은 FAQ를 묶은 것입니다. 비어 있으면 질문을 나누지 않은 것으로 봅니다.
 * history는 답변 LLM에 넘길 이전 대화입니다. 이번 질문이 이전 대화를 써야 할 때만 채우고, 아니면 비어 있습니다.
 * userDataSummary는 가입 정보를 미리 조회하지 않았으면 null이며, 답변에 쓰인 기록을 화면용 카드로 고를 때만 씁니다.
 */
public record AnswerMaterials(
        String question,
        List<FaqSearchResponseDto> faqs,
        List<ContextSection> sections,
        Set<AiTool> tools,
        StoreMapResult storeMap,
        List<SubQuestionFaqs> subQuestions,
        List<ConversationTurn> history,
        Long userId,
        UserDataSummary userDataSummary) {

    public AnswerMaterials {
        // Builder를 거치지 않고 만들어도 컬렉션은 null 없이 불변으로 고정합니다.
        faqs = faqs == null ? List.of() : List.copyOf(faqs);
        sections = sections == null ? List.of() : List.copyOf(sections);
        tools = tools == null || tools.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(tools));
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public AnswerMaterials(
            String question,
            List<FaqSearchResponseDto> faqs,
            List<ContextSection> sections,
            Set<AiTool> tools,
            StoreMapResult storeMap,
            List<SubQuestionFaqs> subQuestions) {
        this(question, faqs, sections, tools, storeMap, subQuestions, List.of(), null, null);
    }

    public AnswerMaterials(
            String question,
            List<FaqSearchResponseDto> faqs,
            List<ContextSection> sections,
            Set<AiTool> tools,
            StoreMapResult storeMap) {
        this(question, faqs, sections, tools, storeMap, List.of(), List.of(), null, null);
    }

    /** 이전 대화를 넣은 사본입니다. 가입 정보 도구가 쓰는 userId와 카드용 userDataSummary도 그대로 옮깁니다. */
    public AnswerMaterials withHistory(List<ConversationTurn> values) {
        return new AnswerMaterials(question, faqs, sections, tools, storeMap, subQuestions, values, userId,
                userDataSummary);
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
        private List<SubQuestionFaqs> subQuestions = List.of();
        private Long userId;
        private UserDataSummary userDataSummary;

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

        public Builder subQuestions(List<SubQuestionFaqs> values) {
            subQuestions = values;
            return this;
        }

        /** 도구가 서버 문맥(ToolContext)으로 받을 로그인 사용자 ID입니다. LLM에는 보이지 않습니다. */
        public Builder userId(Long value) {
            userId = value;
            return this;
        }

        /** 화면 카드용으로만 쓰는 조회 결과입니다. LLM에는 이 객체가 아니라 핸들러가 만든 요약 문자열이 들어갑니다. */
        public Builder userDataSummary(UserDataSummary value) {
            userDataSummary = value;
            return this;
        }

        public AnswerMaterials build() {
        	// 복사와 불변 처리는 생성자가 맡습니다.
            return new AnswerMaterials(question, faqs, sections, tools, storeMap, subQuestions, List.of(), userId,
                    userDataSummary);
        }
    }
}
