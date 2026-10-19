package com.ubot.prompt.service;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.format.SubQuestionFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 질문 분리·멀티턴 판정 LLM에 보낼 요청을 만듭니다. 답변 프롬프트(PromptService)와 파일·형식이 따로입니다. */
@Service
public class SubQuestionPromptService {

    private static final Pattern INPUT_PLACEHOLDER = Pattern.compile("\\{\\{(question|history)\\}\\}");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]+");
    private static final String EMPTY_VALUE = "(없음)";
    private static final String FAILED_ANSWER = "(답변 실패)";
    // 이전 답변이 길면 판정 요청이 커지므로 앞부분만 넘깁니다. 가리키는 대상은 대부분 답변 앞쪽에 있습니다.
    static final int MAX_ANSWER_LENGTH = 500;

    private final Resource systemPromptResource;
    private final Resource userPromptResource;

    public SubQuestionPromptService(
            @Value("${prompt.sub-question.system-location:classpath:prompts/sub-question-system.txt}")
            Resource systemPromptResource,
            @Value("${prompt.sub-question.user-location:classpath:prompts/sub-question-user.txt}")
            Resource userPromptResource) {
        this.systemPromptResource = systemPromptResource;
        this.userPromptResource = userPromptResource;
    }

    /** history는 오래된 턴부터 놓입니다. */
    public LlmRequestDto createPrompt(String question, List<ConversationTurn> history) {
        String systemPrompt = readPrompt(systemPromptResource);
        String userTemplate = readPrompt(userPromptResource);
        if (!StringUtils.hasText(systemPrompt) || !StringUtils.hasText(userTemplate)
                || !userTemplate.contains("{{question}}") || !userTemplate.contains("{{history}}")) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY);
        }
        if (!StringUtils.hasText(question)) {
            throw new PromptException(PromptErrorCode.PROMPT_INPUT_MISSING);
        }

        String historyText = history == null || history.isEmpty() ? EMPTY_VALUE : formatHistory(history);
        // 자리표시자만 한 번 치환합니다. 질문이나 이전 답변 안의 {{question}}, $, 역슬래시는 다시 해석하지 않습니다.
        String userPrompt = INPUT_PLACEHOLDER.matcher(userTemplate).replaceAll(match ->
                Matcher.quoteReplacement(match.group(1).equals("question") ? question : historyText));

        return new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.SYSTEM, systemPrompt),
                new LlmMessageRequestDto(LlmMessageRole.USER, userPrompt)))
                .withResponseSchema(SubQuestionFormat.schema());
    }

    /** 사용자 템플릿의 {{question}}, {{history}}는 입력으로 치환되므로 이름 자체를 유출 표지로 세지 않습니다. */
    public Set<String> englishWords() {
        String systemPrompt = readPrompt(systemPromptResource);
        String userTemplate = INPUT_PLACEHOLDER.matcher(readPrompt(userPromptResource)).replaceAll("");
        String promptText = systemPrompt + "\n" + userTemplate;
        return ENGLISH_WORD.matcher(promptText).results()
                .map(match -> match.group().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String formatHistory(List<ConversationTurn> history) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < history.size(); index++) {
            ConversationTurn turn = history.get(index);
            // 이전 질문은 사용자가 입력한 원문을 넣습니다. 판정 LLM이 다시 쓴 검색어를 넣으면 지어낸 말이 다음 턴으로 옮겨 갑니다.
            text.append('[').append(index + 1).append("] 질문: ")
                    .append(turn.question()).append('\n')
                    .append("답변: ").append(turn.isAnswered() ? truncate(turn.answer()) : FAILED_ANSWER)
                    .append("\n\n");
        }
        return text.toString().strip();
    }

    private String truncate(String answer) {
        return answer.length() <= MAX_ANSWER_LENGTH ? answer : answer.substring(0, MAX_ANSWER_LENGTH) + "…";
    }

    private String readPrompt(Resource resource) {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY, exception);
        }
    }
}
