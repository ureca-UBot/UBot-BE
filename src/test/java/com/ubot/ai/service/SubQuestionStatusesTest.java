package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import com.ubot.ai.dto.LookupFailure;
import java.util.List;
import java.util.Map;
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
        assertThat(SubQuestionStatuses.notice(keys("ANSWER", "ANSWER"), List.of("가", "나"))).isEmpty();
    }

    @Test
    void 하위_질문이_하나이거나_모두_같은_이유로_답하지_못했으면_전체_고지_하나만_낸다() {
        assertThat(SubQuestionStatuses.notice(keys("ABSTAIN"), List.of()))
                .isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(SubQuestionStatuses.notice(keys("ABSTAIN", "ABSTAIN"), List.of("가", "나")))
                .isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(SubQuestionStatuses.notice(keys("OUT_OF_SCOPE"), List.of()))
                .isEqualTo("문의하신 내용은 통신 서비스 상담 범위 밖이라 안내해 드리기 어렵습니다.");
    }

    @Test
    void 일부만_답하지_못했으면_status별로_원문_조각을_묶어_고지한다() {
        String notice = SubQuestionStatuses.notice(keys("ABSTAIN", "ANSWER", "ABSTAIN", "OUT_OF_SCOPE"),
                List.of("요금은 얼마고", "가입은 어디서 해요", "약정은?", "김치찌개 끓이는 법"));

        assertThat(notice).isEqualTo("「요금은 얼마고」, 「약정은」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다. "
                + "「김치찌개 끓이는 법」에 대해서는 통신 서비스 상담 범위 밖이라 안내해 드리기 어렵습니다.");
    }

    @Test
    void 원문_조각이_없으면_순번으로_가리킨다() {
        assertThat(SubQuestionStatuses.notice(keys("ANSWER", "ABSTAIN"), List.of()))
                .isEqualTo("「2번째 질문」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다.");
    }

    @Test
    void 조회가_실패하면_그_조회가_필요한_하위_질문의_ABSTAIN만_사유별로_고지한다() {
        var keys = SubQuestionStatuses.noticeKeys(List.of("ABSTAIN", "ABSTAIN", "ANSWER", "ABSTAIN"),
                List.of(List.of(LookupFailure.Target.USER_DATA), List.of(), List.of(LookupFailure.Target.USER_DATA),
                        List.of(LookupFailure.Target.STORE_DATA)),
                Map.of(LookupFailure.Target.USER_DATA, LookupFailure.of(LookupFailure.Kind.USER_NOT_FOUND),
                        LookupFailure.Target.STORE_DATA, LookupFailure.of(LookupFailure.Kind.STORE_PLACE_NOT_FOUND, "강남역")));

        assertThat(SubQuestionStatuses.notice(keys, List.of("내 요금", "약정 할인", "가입 방법", "근처 매장")))
                .isEqualTo("「내 요금」에 대해서는 가입 정보를 찾을 수 없어 확인해 드리기 어렵습니다. "
                        + "「약정 할인」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다. "
                        + "「근처 매장」에 대해서는 '강남역' 위치를 찾지 못했습니다. 정확한 지명이나 역 이름으로 다시 질문해 주세요.");
    }

    @Test
    void 질문_전체를_조회_실패로_답하지_못했으면_사유별_전체_고지를_낸다() {
        var failure = LookupFailure.of(LookupFailure.Kind.SUBSCRIPTION_UNSELECTED, "010-****-1234(5G 스탠다드), 010-****-5678");
        var keys = SubQuestionStatuses.noticeKeys(List.of("PARTIAL"), List.of(List.of(LookupFailure.Target.USER_DATA)),
                Map.of(LookupFailure.Target.USER_DATA, failure));

        assertThat(SubQuestionStatuses.notice(keys, List.of()))
                .isEqualTo("어느 회선인지 정할 수 없습니다. 다음 중 어느 번호인지 알려 주세요: 010-****-1234(5G 스탠다드), 010-****-5678");
        // 조회 실패가 없으면 칸을 합친 PARTIAL은 일반 전체 고지입니다.
        assertThat(SubQuestionStatuses.notice(keys("PARTIAL"), List.of()))
                .isEqualTo("문의하신 내용 중 일부는 현재 안내 자료로는 확인해 드리기 어렵습니다.");
    }

    @Test
    void 본문과_고지는_빈_줄로_잇고_한쪽이_비면_다른_쪽만_쓴다() {
        assertThat(SubQuestionStatuses.compose("답변입니다.", "고지입니다.")).isEqualTo("답변입니다.\n\n고지입니다.");
        assertThat(SubQuestionStatuses.compose("", "고지입니다.")).isEqualTo("고지입니다.");
        assertThat(SubQuestionStatuses.compose("답변입니다.", "")).isEqualTo("답변입니다.");
    }

    private static List<SubQuestionStatuses.NoticeKey> keys(String... statuses) {
        return SubQuestionStatuses.statusKeys(List.of(statuses));
    }
}
