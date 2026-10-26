package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class QuestionMonthsTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Test
    void 질문에_나온_달과_범위와_최근_N개월을_달로_바꾼다() {
        assertThat(QuestionMonths.find("8월 9월 요금 알려줘", OCTOBER, 3).months())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9));
        assertThat(QuestionMonths.find("8~10월 청구서", OCTOBER, 3).months())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(QuestionMonths.find("8월부터 10월까지 요금", OCTOBER, 3).months())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(QuestionMonths.find("최근 3개월 요금 알려줘", OCTOBER, 3).months())
                .containsExactly(OCTOBER, YearMonth.of(2026, 9), YearMonth.of(2026, 8));
        assertThat(QuestionMonths.find("지난 두 달 요금", OCTOBER, 3).months())
                .containsExactly(OCTOBER, YearMonth.of(2026, 9));
        // 기준 달보다 뒤의 달은 작년으로 봅니다.
        assertThat(QuestionMonths.find("11월 요금", OCTOBER, 3).months()).containsExactly(YearMonth.of(2025, 11));
        assertThat(QuestionMonths.find("2025년 12월 청구서", OCTOBER, 3).months()).containsExactly(YearMonth.of(2025, 12));
    }

    @Test
    void 범위는_말한_연도를_따르고_해를_넘겨도_이어지며_한_해_안에서_해석한다() {
        // 범위 앞의 연도를 끝 달까지 적용하고, 범위 안의 "10월"을 올해로 따로 읽지 않습니다.
        assertThat(QuestionMonths.find("2025년 8월부터 10월까지 청구서 알려줘", OCTOBER, 3).months())
                .containsExactly(YearMonth.of(2025, 8), YearMonth.of(2025, 9), YearMonth.of(2025, 10));
        assertThat(QuestionMonths.find("2025년 11월~2026년 1월 요금", OCTOBER, 3).months())
                .containsExactly(YearMonth.of(2025, 11), YearMonth.of(2025, 12), YearMonth.of(2026, 1));
        // 시작 달이 끝 달보다 크면 해를 넘긴 범위입니다.
        assertThat(QuestionMonths.find("11월~1월 요금", OCTOBER, 3).months())
                .containsExactly(YearMonth.of(2025, 11), YearMonth.of(2025, 12), YearMonth.of(2026, 1));
        // 끝 달이 기준 달보다 뒤면 범위 전체를 작년으로 봅니다(2026-08, 2026-09, 2025-10으로 갈라지지 않음).
        assertThat(QuestionMonths.find("8~10월 청구서", YearMonth.of(2026, 9), 3).months())
                .containsExactly(YearMonth.of(2025, 8), YearMonth.of(2025, 9), YearMonth.of(2025, 10));
        // 범위 안의 "사용" 표현도 그대로 표시합니다.
        assertThat(QuestionMonths.find("8월부터 9월 사용분 요금", OCTOBER, 3).usageMonthMentioned()).isTrue();
    }

    @Test
    void 개수를_제한하고_날짜는_달로_보지_않는다() {
        assertThat(QuestionMonths.find("최근 6개월 요금", OCTOBER, 3).months()).hasSize(3);
        assertThat(QuestionMonths.find("8월 5일에 낸 요금", OCTOBER, 3).months()).isEmpty();
    }

    @Test
    void 사용한_달로_말한_표현을_표시한다() {
        assertThat(QuestionMonths.find("9월 사용분 요금", OCTOBER, 3).usageMonthMentioned()).isTrue();
        assertThat(QuestionMonths.find("9월 요금", OCTOBER, 3).usageMonthMentioned()).isFalse();
    }

    @Test
    void 여러_달을_묻는지와_달_사이_계산을_묻는지를_가린다() {
        assertThat(QuestionMonths.asksMultipleMonths("8월 9월 요금 알려줘")).isTrue();
        assertThat(QuestionMonths.asksMultipleMonths("최근 3개월 요금")).isTrue();
        assertThat(QuestionMonths.asksMultipleMonths("9월 요금 알려줘")).isFalse();
        assertThat(QuestionMonths.asksMultipleMonths("지난달 요금")).isFalse();
        assertThat(QuestionMonths.asksComparison("8월이랑 9월 요금 비교해줘")).isTrue();
        assertThat(QuestionMonths.asksComparison("8월 9월 요금 차이")).isTrue();
        assertThat(QuestionMonths.asksComparison("8월 9월 요금 알려줘")).isFalse();
    }
}
