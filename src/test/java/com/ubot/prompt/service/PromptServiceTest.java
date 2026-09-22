package com.ubot.prompt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.faq.dto.FaqSearchResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.prompt.exception.PromptException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class PromptServiceTest {

    // 연결 검증용 문구입니다. 실제 서비스의 최종 프롬프트가 아닙니다.
    private static final String TEST_SYSTEM = "연결 테스트용 시스템 메시지";
    private static final String TEST_TEMPLATE = "질문:\n{{question}}\n참고자료:\n{{faqs}}";
    private final List<FaqSearchResponseDto> results = List.of(
            new FaqSearchResponseDto(12L, "유심 재발급", "매장에서 재발급 가능합니다.", 0.9),
            new FaqSearchResponseDto(13L, "유심 재발급 준비물", "준비물 안내 원문", 0.6));

    @Test
    void 원래_질문과_FAQ_전체_원문을_사용자_메시지에_넣는다() {
        var service = service(TEST_SYSTEM, TEST_TEMPLATE);

        var request = service.createPrompt("유심 재발급 방법과 준비물을 알려주세요.", results);

        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().getFirst().role()).isEqualTo(LlmMessageRole.SYSTEM);
        assertThat(request.messages().getFirst().content()).isEqualTo(TEST_SYSTEM);
        assertThat(request.messages().getLast().role()).isEqualTo(LlmMessageRole.USER);
        assertThat(request.messages().getLast().content())
                .contains("유심 재발급 방법과 준비물을 알려주세요.", "[FAQ ID: 12]", "[FAQ ID: 13]",
                        "유심 재발급 준비물", "매장에서 재발급 가능합니다.", "준비물 안내 원문")
                .doesNotContain("0.9", "0.6");
    }

    @Test
    void 질문과_FAQ의_자리표시자나_특수문자는_다시_치환하지_않는다() {
        var service = service(TEST_SYSTEM, TEST_TEMPLATE);
        String question = "질문 안의 {{faqs}}와 $1, \\ 는 원문 그대로";
        String faqAnswer = "FAQ 안의 {{question}}, {{faqs}}와 $2, \\ 를 보존";

        var request = service.createPrompt(question,
                List.of(new FaqSearchResponseDto(1L, "FAQ 질문", faqAnswer, 0.9)));

        assertThat(request.messages().getLast().content()).contains(question, faqAnswer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n ", "{{question}}만 존재", "{{faqs}}만 존재"})
    void 미완성_사용자_템플릿은_호출_전에_거부한다(String template) {
        assertThatThrownBy(() -> service(TEST_SYSTEM, template).createPrompt("질문", results))
                .isInstanceOf(PromptException.class)
                .hasMessage("답변 프롬프트가 아직 준비되지 않았습니다.");
    }

    @Test
    void 시스템_지침이_비어_있으면_거부한다() {
        assertThatThrownBy(() -> service(" \n ", TEST_TEMPLATE).createPrompt("질문", results))
                .isInstanceOf(PromptException.class);
    }

    @Test
    void 프롬프트_파일이_없으면_준비중_오류를_반환한다() {
        var service = new PromptService(new ClassPathResource("missing-faq-prompt.txt"), resource(TEST_TEMPLATE));

        assertThatThrownBy(() -> service.createPrompt("질문", results))
                .isInstanceOf(PromptException.class)
                .hasMessage("답변 프롬프트가 아직 준비되지 않았습니다.");
    }

    @Test
    void 질문이나_FAQ가_없으면_요청을_구성하지_않는다() {
        var service = service(TEST_SYSTEM, TEST_TEMPLATE);

        assertThatThrownBy(() -> service.createPrompt(" ", results)).isInstanceOf(PromptException.class);
        assertThatThrownBy(() -> service.createPrompt("질문", List.of())).isInstanceOf(PromptException.class);
    }

    @Test
    void FAQ_답변이_누락되면_거부한다() {
        assertThatThrownBy(() -> service(TEST_SYSTEM, TEST_TEMPLATE).createPrompt("질문",
                List.of(new FaqSearchResponseDto(1L, "FAQ 질문", null, 0.9))))
                .isInstanceOf(PromptException.class);
    }

    private PromptService service(String system, String user) {
        return new PromptService(resource(system), resource(user));
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
