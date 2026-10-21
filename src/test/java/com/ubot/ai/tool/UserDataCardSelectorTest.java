package com.ubot.ai.tool;

import static com.ubot.ai.tool.UserDataFixtures.billing;
import static com.ubot.ai.tool.UserDataFixtures.contract;
import static com.ubot.ai.tool.UserDataFixtures.plan;
import static com.ubot.ai.tool.UserDataFixtures.subscription;
import static com.ubot.ai.tool.UserDataFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.dto.UserDataSummary.Profile;
import com.ubot.ai.dto.UserDataSummary.SubscriptionSummary;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserDataCardSelectorTest {

    // 요약에는 2026-10 청구서(미납)와 2026-08 연체 청구서, 2026-10 사용량(데이터 12.3GB 사용, 잔여 137.7GB, 음성 120분)이 들어 있습니다.
    private static final UserDataSummary ONE_LINE = summary(line(1L, "010-1234-5678"));

    @Test
    void 답변에_요금제_이름이_나오면_요금제_카드를_만든다() {
        UserDataCards cards = UserDataCardSelector.select("월정액 얼마야?", "5G 스탠다드는 월 75,000원입니다.", ONE_LINE);

        assertThat(cards.plans()).singleElement().satisfies(card -> {
            assertThat(card.phoneNumber()).isEqualTo("010-****-5678");
            assertThat(card.plan().summary().name()).isEqualTo("5G 스탠다드");
        });
        assertThat(cards.usages()).isEmpty();
        assertThat(cards.billings()).isEmpty();
    }

    @Test
    void 요금제_질문이면_답변에_이름이_없어도_요금제_카드를_만든다() {
        UserDataCards cards = UserDataCardSelector.select("제 요금제 통화 무제한인가요?", "네, 무제한입니다.", ONE_LINE);

        assertThat(cards.plans()).hasSize(1);
    }

    @Test
    void 질문으로_붙이는_규칙은_요금제에만_쓴다() {
        // 청구서·사용량은 답변에 그 기록이 나와야만 붙입니다.
        UserDataCards cards = UserDataCardSelector.select("이번 달 요금 얼마야? 데이터는?", "61,950원입니다.", ONE_LINE);

        assertThat(cards.plans()).isEmpty();
        assertThat(cards.billings()).isEmpty();
        assertThat(cards.usages()).isEmpty();
    }

    @Test
    void 답변에_나온_청구서마다_카드를_만들고_사용_월을_함께_담는다() {
        UserDataCards cards = UserDataCardSelector.select("미납 있어?",
                "2026년 10월 청구서 78,000원과 8월 청구서 78,000원이 미납입니다.", ONE_LINE);

        assertThat(cards.billings()).extracting(card -> card.billing().billingMonth(), UserDataCards.BillingCard::usageMonth)
                .containsExactly(
                        tuple(YearMonth.of(2026, 10), 9),
                        tuple(YearMonth.of(2026, 8), 7));
    }

    @Test
    void 요약에_없는_달의_청구서나_사용분_표기는_카드로_만들지_않는다() {
        // "9월 청구서"는 도구로 조회한 달이라 요약에 없고, "8월 사용분"은 청구서 표기가 아닙니다.
        UserDataCards cards = UserDataCardSelector.select("지난달 청구서 보여줘",
                "2026년 9월 청구서(8월 사용분) 기준으로 안내드립니다.", ONE_LINE);

        assertThat(cards.billings()).isEmpty();
    }

    @Test
    void 월_숫자가_다른_달의_일부로_걸리지_않는다() {
        UserDataSummary january = summary(new SubscriptionSummary(subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE),
                plan(), null, billing(YearMonth.of(2026, 1), PaymentStatus.PAID), List.of(), null,
                List.of(), List.of(), List.of()));

        assertThat(UserDataCardSelector.select("요금", "11월 청구서는 없습니다.", january).billings()).isEmpty();
        assertThat(UserDataCardSelector.select("요금", "1월 청구서는 78,000원입니다.", january).billings()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"데이터는 12.3GB 쓰셨습니다.", "남은 데이터는 137.7GB입니다.", "통화는 120분 하셨습니다."})
    void 답변에_사용량_값이_나오면_사용량_카드를_만든다(String answer) {
        UserDataCards cards = UserDataCardSelector.select("이번 달 사용량", answer, ONE_LINE);

        assertThat(cards.usages()).singleElement()
                .satisfies(card -> assertThat(card.usage().billingMonth()).isEqualTo(YearMonth.of(2026, 10)));
    }

    @Test
    void 숫자가_다른_값의_일부일_때는_사용량으로_보지_않는다() {
        assertThat(UserDataCardSelector.select("데이터", "212.3GB와 1120분입니다.", ONE_LINE).usages()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"결합 상품은 없습니다.", "소액결제 정보는 확인할 수 없습니다."})
    void 기록을_쓰지_않은_답변에는_카드를_만들지_않는다(String answer) {
        UserDataCards cards = UserDataCardSelector.select("결합 상품 있어?", answer, ONE_LINE);

        assertThat(cards.plans()).isEmpty();
        assertThat(cards.usages()).isEmpty();
        assertThat(cards.billings()).isEmpty();
    }

    @Test
    void 회선이_여러_개면_회선마다_판별하고_질문의_끝_4자리로_요금제_카드를_좁힌다() {
        UserDataSummary twoLines = summary(line(1L, "010-1234-5678"), line(2L, "010-9999-0001"));

        assertThat(UserDataCardSelector.select("내 요금제 뭐야?", "두 회선 모두 5G 스탠다드입니다.", twoLines).plans())
                .extracting(UserDataCards.PlanCard::phoneNumber)
                .containsExactly("010-****-5678", "010-****-0001");
        assertThat(UserDataCardSelector.select("0001 회선 요금제 알려줘", "네, 무제한 요금제입니다.", twoLines).plans())
                .extracting(UserDataCards.PlanCard::phoneNumber)
                .containsExactly("010-****-0001");
    }

    @Test
    void 질문이_회선을_지정하면_다른_회선의_같은_달_청구서와_같은_값_카드를_붙이지_않는다() {
        // 두 회선은 요금제·사용량·청구서가 모두 같아 답변 문자열만으로는 회선을 가릴 수 없습니다.
        UserDataSummary twoLines = summary(line(1L, "010-1234-5678"), line(2L, "010-9999-0001"));
        String answer = "10월 청구서는 78,000원이고 5G 스탠다드 요금제입니다. 데이터는 12.3GB 쓰셨습니다.";

        UserDataCards asked = UserDataCardSelector.select("0001 회선 이번 달 요금 얼마야?", answer, twoLines);

        assertThat(asked.plans()).extracting(UserDataCards.PlanCard::phoneNumber).containsExactly("010-****-0001");
        assertThat(asked.usages()).extracting(UserDataCards.UsageCard::phoneNumber).containsExactly("010-****-0001");
        assertThat(asked.billings()).extracting(UserDataCards.BillingCard::phoneNumber).containsExactly("010-****-0001");

        // 회선을 지정하지 않으면 회선별로 안내한 것으로 보고 두 회선 모두 붙입니다.
        UserDataCards all = UserDataCardSelector.select("이번 달 요금 얼마야?", answer, twoLines);

        assertThat(all.billings()).extracting(UserDataCards.BillingCard::phoneNumber)
                .containsExactly("010-****-5678", "010-****-0001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 답변이_비었으면_카드를_만들지_않는다(String answer) {
        assertThat(UserDataCardSelector.select("내 요금제 뭐야?", answer, ONE_LINE)).isEqualTo(UserDataCards.EMPTY);
    }

    @Test
    void 요약이_없으면_카드를_만들지_않는다() {
        assertThat(UserDataCardSelector.select("내 요금제 뭐야?", "5G 스탠다드입니다.", null)).isEqualTo(UserDataCards.EMPTY);
    }

    private static SubscriptionSummary line(Long id, String phoneNumber) {
        return new SubscriptionSummary(subscription(id, phoneNumber, SubscriptionStatus.ACTIVE), plan(),
                usage(YearMonth.of(2026, 10)), billing(YearMonth.of(2026, 10), PaymentStatus.UNPAID),
                List.of(billing(YearMonth.of(2026, 10), PaymentStatus.UNPAID), billing(YearMonth.of(2026, 8), PaymentStatus.OVERDUE)),
                contract(), List.of(), List.of(), List.of());
    }

    private static UserDataSummary summary(SubscriptionSummary... subscriptions) {
        return new UserDataSummary(LocalDate.of(2026, 10, 12),
                new Profile("홍길동", 30, "서울", LocalDate.of(2024, 1, 1)), List.of(subscriptions));
    }
}
