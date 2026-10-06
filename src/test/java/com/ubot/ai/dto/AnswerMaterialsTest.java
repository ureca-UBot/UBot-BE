package com.ubot.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.ai.tool.AiTool;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerMaterialsTest {

    @Test
    void build_결과는_이후_변경에_영향받지_않고_수정할_수_없다() {
        var faqs = new ArrayList<>(List.of(new FaqSearchResponseDto(1L, "질문", "답변", 0.9, Intent.GENERAL)));
        var builder = AnswerMaterials.builder("질문").addFaqs(faqs).enableTool(AiTool.STORE_SEARCH);

        AnswerMaterials materials = builder.build();
        faqs.add(new FaqSearchResponseDto(2L, "질문2", "답변2", 0.8, Intent.GENERAL));
        builder.addSection(new ContextSection("사용자 정보", "내용"));

        assertThat(materials.faqs()).hasSize(1);
        assertThat(materials.sections()).isEmpty();
        assertThatThrownBy(() -> materials.faqs().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> materials.sections().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> materials.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 아무것도_넣지_않으면_빈_자료다() {
        AnswerMaterials materials = AnswerMaterials.builder("질문").build();

        assertThat(materials.faqs()).isEmpty();
        assertThat(materials.sections()).isEmpty();
        assertThat(materials.tools()).isEmpty();
        assertThat(materials.storeMap()).isNull();
    }

    @Test
    void 범위를_벗어난_위치와_빈_섹션은_만들_수_없다() {
        assertThatThrownBy(() -> new Location(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Location(0, -181)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSection(" ", "내용")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSection("제목", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Location(Double.NaN, 127.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Location(37.5, Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }
}
