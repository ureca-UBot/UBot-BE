package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubQuestionStatusesTest {

    @Test
    void 하위_status를_조합해_전체_status를_정한다() {
        assertThat(SubQuestionStatuses.combine(List.of("ANSWER", "ANSWER"))).isEqualTo("ANSWER");
        assertThat(SubQuestionStatuses.combine(List.of("ANSWER", "ABSTAIN"))).isEqualTo("PARTIAL");
        assertThat(SubQuestionStatuses.combine(List.of("ANSWER", "OUT_OF_SCOPE"))).isEqualTo("PARTIAL");
        assertThat(SubQuestionStatuses.combine(List.of("ABSTAIN", "ABSTAIN"))).isEqualTo("ABSTAIN");
        assertThat(SubQuestionStatuses.combine(List.of("OUT_OF_SCOPE", "ABSTAIN"))).isEqualTo("ABSTAIN");
        assertThat(SubQuestionStatuses.combine(List.of("ABSTAIN", "CONFLICT", "OUT_OF_SCOPE"))).isEqualTo("CONFLICT");
        assertThat(SubQuestionStatuses.combine(List.of())).isNull();
        assertThat(SubQuestionStatuses.combine(Arrays.asList("ANSWER", null))).isNull();
    }

    @Test
    void 모두_답했으면_고지가_없다() {
        assertThat(SubQuestionStatuses.notice(List.of("ANSWER", "ANSWER"), List.of("가", "나"))).isEmpty();
    }

    @Test
    void 하위_질문이_하나이거나_모두_같은_이유로_답하지_못했으면_전체_고지_하나만_낸다() {
        assertThat(SubQuestionStatuses.notice(List.of("ABSTAIN"), List.of()))
                .isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(SubQuestionStatuses.notice(List.of("ABSTAIN", "ABSTAIN"), List.of("가", "나")))
                .isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(SubQuestionStatuses.notice(List.of("OUT_OF_SCOPE"), List.of()))
                .isEqualTo("문의하신 내용은 통신 서비스 상담 범위 밖이라 안내해 드리기 어렵습니다.");
    }

    @Test
    void 일부만_답하지_못했으면_status별로_원문_조각을_묶어_고지한다() {
        String notice = SubQuestionStatuses.notice(List.of("ABSTAIN", "ANSWER", "ABSTAIN", "OUT_OF_SCOPE"),
                List.of("요금은 얼마고", "가입은 어디서 해요", "약정은?", "김치찌개 끓이는 법"));

        assertThat(notice).isEqualTo("「요금은 얼마고」, 「약정은」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다. "
                + "「김치찌개 끓이는 법」에 대해서는 통신 서비스 상담 범위 밖이라 안내해 드리기 어렵습니다.");
    }

    @Test
    void 원문_조각이_없으면_순번으로_가리킨다() {
        assertThat(SubQuestionStatuses.notice(List.of("ANSWER", "ABSTAIN"), List.of()))
                .isEqualTo("「2번째 질문」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다.");
    }

    @Test
    void 본문과_고지는_빈_줄로_잇고_한쪽이_비면_다른_쪽만_쓴다() {
        assertThat(SubQuestionStatuses.compose("답변입니다.", "고지입니다.")).isEqualTo("답변입니다.\n\n고지입니다.");
        assertThat(SubQuestionStatuses.compose("", "고지입니다.")).isEqualTo("고지입니다.");
        assertThat(SubQuestionStatuses.compose("답변입니다.", "")).isEqualTo("답변입니다.");
    }
}
