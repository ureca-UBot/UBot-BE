package com.ubot.ai.tool;

import static com.ubot.ai.tool.UserDataFixtures.billing;
import static com.ubot.ai.tool.UserDataFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataCards.BillingCard;
import com.ubot.ai.dto.UserDataCards.UsageCard;
import com.ubot.billing.enums.PaymentStatus;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlyRecordsAnswerTest {

    private static final String PHONE = "010-****-5678";

    @Test
    void 여러_달을_물으면_조회한_청구서를_달_순서대로_한_줄씩_쓰고_같은_기록은_한_번만_쓴다() {
        BillingCard september = BillingCard.of(PHONE, billing(YearMonth.of(2026, 9), PaymentStatus.PAID));
        BillingCard august = BillingCard.of(PHONE, billing(YearMonth.of(2026, 8), PaymentStatus.PAID));
        var recorded = new UserDataCards(List.of(), List.of(), List.of(september, august, september));

        var monthly = MonthlyRecordsAnswer.of("8월 9월 요금 알려줘", recorded).orElseThrow();

        assertThat(monthly.answer()).isEqualTo(MonthlyRecordsAnswer.BILLING_HEADER
                + "\n- " + UserDataSummarizer.formatBilling(august.billing())
                + "\n- " + UserDataSummarizer.formatBilling(september.billing()));
        assertThat(monthly.cards().billings()).containsExactly(august, september);
    }

    @Test
    void 여러_달_사용량도_한_줄씩_쓴다() {
        UsageCard september = new UsageCard(PHONE, usage(YearMonth.of(2026, 9)));
        UsageCard august = new UsageCard(PHONE, usage(YearMonth.of(2026, 8)));

        var monthly = MonthlyRecordsAnswer.of("8월 9월 데이터 사용량",
                new UserDataCards(List.of(), List.of(september, august), List.of())).orElseThrow();

        assertThat(monthly.answer()).startsWith(MonthlyRecordsAnswer.USAGE_HEADER + "\n- 2026-08 사용량");
        assertThat(monthly.cards().usages()).containsExactly(august, september);
    }

    @Test
    void 한_달만_묻거나_비교를_묻거나_한_달만_조회했으면_대신하지_않는다() {
        BillingCard september = BillingCard.of(PHONE, billing(YearMonth.of(2026, 9), PaymentStatus.PAID));
        BillingCard august = BillingCard.of(PHONE, billing(YearMonth.of(2026, 8), PaymentStatus.PAID));
        var two = new UserDataCards(List.of(), List.of(), List.of(september, august));

        assertThat(MonthlyRecordsAnswer.of("9월 요금 알려줘", two)).isEmpty();
        assertThat(MonthlyRecordsAnswer.of("8월이랑 9월 요금 비교해줘", two)).isEmpty();
        assertThat(MonthlyRecordsAnswer.of("8월 9월 요금 알려줘",
                new UserDataCards(List.of(), List.of(), List.of(september)))).isEmpty();
        // 청구서와 사용량을 함께 조회한 질문은 모델이 답합니다.
        assertThat(MonthlyRecordsAnswer.of("8월 9월 요금이랑 사용량", new UserDataCards(List.of(),
                List.of(new UsageCard(PHONE, usage(YearMonth.of(2026, 9)))), List.of(september, august)))).isEmpty();
    }
}
