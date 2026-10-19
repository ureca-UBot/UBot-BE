package com.ubot.prompt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.format.SubQuestionFormat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("판정 LLM 프롬프트 구성 테스트")
class SubQuestionPromptServiceTest {

    private final SubQuestionPromptService service = new SubQuestionPromptService(
            new ClassPathResource("prompts/sub-question-system.txt"),
            new ClassPathResource("prompts/sub-question-user.txt"));

    @Test
    @DisplayName("이전 대화를 오래된 순으로 번호를 붙여 사용자 원문과 함께 넣고, 실패한 턴은 답변 실패로 표시한다")
    void formatsHistory() {
        var request = service.createPrompt("그럼 요금은?", List.of(
                new ConversationTurn("5G 프리미엄 요금이랑 데이터 알려줘", "월 89,000원입니다."),
                new ConversationTurn("해외 로밍 신청", null)));

        assertThat(request.messages()).extracting(message -> message.role())
                .containsExactly(LlmMessageRole.SYSTEM, LlmMessageRole.USER);
        assertThat(request.messages().getLast().content()).isEqualTo("""
                이전 대화:
                [1] 질문: 5G 프리미엄 요금이랑 데이터 알려줘
                답변: 월 89,000원입니다.

                [2] 질문: 해외 로밍 신청
                답변: (답변 실패)

                현재 질문:
                그럼 요금은?
                """);
        assertThat(request.responseSchema()).isEqualTo(SubQuestionFormat.schema());
        assertThat(request.hasTools()).isFalse();
    }

    @Test
    @DisplayName("이전 대화가 없으면 (없음)으로 넣는다")
    void emptyHistory() {
        var request = service.createPrompt("유심 재발급", List.of());

        assertThat(request.messages().getLast().content()).startsWith("이전 대화:\n(없음)\n\n현재 질문:\n유심 재발급");
    }

    @Test
    @DisplayName("이전 답변이 길면 앞부분만 넣는다")
    void truncatesLongAnswer() {
        String answer = "가".repeat(SubQuestionPromptService.MAX_ANSWER_LENGTH + 10);

        var request = service.createPrompt("그거 얼마야?", List.of(new ConversationTurn("질문", answer)));

        assertThat(request.messages().getLast().content())
                .contains("답변: " + "가".repeat(SubQuestionPromptService.MAX_ANSWER_LENGTH) + "…\n")
                .doesNotContain("가".repeat(SubQuestionPromptService.MAX_ANSWER_LENGTH + 1));
    }

    @Test
    @DisplayName("질문이나 이전 답변 안의 자리표시자는 다시 치환하지 않는다")
    void doesNotReplaceTwice() {
        var request = service.createPrompt("{{history}} $1",
                List.of(new ConversationTurn("{{question}}", "\\ 답변")));

        assertThat(request.messages().getLast().content())
                .contains("[1] 질문: {{question}}\n답변: \\ 답변", "현재 질문:\n{{history}} $1");
    }

    @Test
    @DisplayName("영문 유출 표지는 실제 프롬프트에서 읽고 입력 자리표시자는 제외한다")
    void extractsEnglishWordsWithoutInputPlaceholders() {
        assertThat(service.englishWords()).contains("faq", "json", "sub", "questions")
                .doesNotContain("question", "history", "esim");
        var customized = new SubQuestionPromptService(
                new ByteArrayResource("CustomMarker FAQ".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("Conversation {{history}} Question {{question}}".getBytes(StandardCharsets.UTF_8)));
        assertThat(customized.englishWords()).containsExactlyInAnyOrder("custommarker", "faq", "conversation", "question");
    }

    @Test
    @DisplayName("치환하지 않는 시스템 프롬프트의 같은 표기는 유출 표지에서 지우지 않는다")
    void preservesLiteralPlaceholderNamesInSystemPrompt() {
        var customized = new SubQuestionPromptService(
                new ByteArrayResource("{{question}} {{history}} FAQ".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayResource("{{question}} {{history}}".getBytes(StandardCharsets.UTF_8)));

        assertThat(customized.englishWords()).containsExactlyInAnyOrder("question", "history", "faq");
    }

    @Test
    @DisplayName("사용자 템플릿에 자리표시자가 빠지면 준비되지 않은 프롬프트로 실패한다")
    void rejectsTemplateWithoutPlaceholder() {
        var broken = new SubQuestionPromptService(
                new ClassPathResource("prompts/sub-question-system.txt"),
                new ByteArrayResource("현재 질문:\n{{question}}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> broken.createPrompt("유심 재발급", List.of()))
                .isInstanceOfSatisfying(PromptException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(PromptErrorCode.PROMPT_NOT_READY));
    }
}
