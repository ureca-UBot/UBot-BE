package com.ubot.prompt.service;

import com.ubot.ai.dto.ContextSection;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PromptService {

    private static final Pattern INPUT_PLACEHOLDER = Pattern.compile("\\{\\{(question|faqs|context)\\}\\}");
    private static final String EMPTY_VALUE = "(없음)";

    private final Resource systemPromptResource;
    private final Resource userPromptResource;

    public PromptService(
            @Value("${prompt.faq.system-location:classpath:prompts/faq-system.txt}") Resource systemPromptResource,
            @Value("${prompt.faq.user-location:classpath:prompts/faq-user.txt}") Resource userPromptResource) {
        this.systemPromptResource = systemPromptResource;
        this.userPromptResource = userPromptResource;
    }

    public LlmRequestDto createPrompt(String question, List<FaqSearchResponseDto> faqs, List<ContextSection> sections) {
        // 답변 지침은 코드에 임의로 작성하지 않고, 담당자가 채울 파일에서 읽습니다.
        String[] templates = readTemplates();
        String systemPrompt = templates[0];
        String userTemplate = templates[1];

        // 사용자 정보 같은 추가 자료가 있는데 템플릿에 {{context}} 자리가 없으면, 자료가 빠진 채로 LLM을 호출하지 않도록 막습니다.
        List<ContextSection> contextSections = sections == null ? List.of() : sections;
        if (!contextSections.isEmpty() && !userTemplate.contains("{{context}}")) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY);
        }
        // 질문은 ChatService가 먼저 검증하므로 여기서 비어 있으면 호출 쪽 버그입니다.
        if (!StringUtils.hasText(question)) {
            throw new PromptException(PromptErrorCode.PROMPT_INPUT_MISSING);
        }

        // 검색된 모든 FAQ의 ID·질문·답변을 전달합니다. 점수는 ChatAnswerProcessor의 검색 판단용입니다.
        // 매장만 묻는 질문처럼 FAQ 없이 도구·추가 자료만으로 답할 수도 있습니다.
        String faqText = faqs == null || faqs.isEmpty() ? EMPTY_VALUE : formatFaqs(faqs);
        String contextText = contextSections.isEmpty() ? EMPTY_VALUE : formatSections(contextSections);

        // 템플릿의 자리표시자만 한 번 치환합니다.
        // 사용자 질문이나 FAQ 안의 {{faqs}}, $, 역슬래시 등은 다시 해석하지 않습니다.
        String userPrompt = INPUT_PLACEHOLDER.matcher(userTemplate).replaceAll(match ->
                Matcher.quoteReplacement(switch (match.group(1)) {
                    case "question" -> question;
                    case "faqs" -> faqText;
                    default -> contextText;
                }));

        return new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.SYSTEM, systemPrompt),
                new LlmMessageRequestDto(LlmMessageRole.USER, userPrompt)));
    }

    private String[] readTemplates() {
        String systemPrompt = readPrompt(systemPromptResource);
        String userTemplate = readPrompt(userPromptResource);

        // 빈 프롬프트나 질문/FAQ 삽입 위치가 없는 템플릿으로는 모델을 호출하지 않습니다.
        if (!StringUtils.hasText(systemPrompt) || !StringUtils.hasText(userTemplate)
                || !userTemplate.contains("{{question}}") || !userTemplate.contains("{{faqs}}")) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY);
        }
        return new String[] {systemPrompt, userTemplate};
    }

    private String readPrompt(Resource resource) {
        // 한글 프롬프트를 UTF-8로 읽습니다. 파일 누락도 준비되지 않은 상태로 처리합니다.
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY, exception);
        }
    }

    private String formatFaqs(List<FaqSearchResponseDto> results) {
        StringBuilder context = new StringBuilder();
        for (FaqSearchResponseDto faq : results) {
            if (faq == null || faq.faqId() == null || !StringUtils.hasText(faq.question())
                    || !StringUtils.hasText(faq.answer())) {
                throw new PromptException(PromptErrorCode.PROMPT_FAQ_INVALID);
            }
            context.append("[FAQ ID: ").append(faq.faqId()).append("]\n")
                    .append("질문: ").append(faq.question()).append('\n')
                    .append("답변: ").append(faq.answer()).append("\n\n");
        }
        return context.toString();
    }

    private String formatSections(List<ContextSection> sections) {
        StringBuilder context = new StringBuilder();
        for (ContextSection section : sections) {
            context.append("### ").append(section.title()).append('\n')
                    .append(section.content()).append("\n\n");
        }
        return context.toString();
    }
}
