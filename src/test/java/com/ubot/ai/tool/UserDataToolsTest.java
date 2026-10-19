package com.ubot.ai.tool;

import static com.ubot.ai.tool.UserDataFixtures.CLOCK;
import static com.ubot.ai.tool.UserDataFixtures.billing;
import static com.ubot.ai.tool.UserDataFixtures.page;
import static com.ubot.ai.tool.UserDataFixtures.subscription;
import static com.ubot.ai.tool.UserDataFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.billing.enums.PaymentStatus;
import com.ubot.billing.service.BillingService;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.exception.SubscriptionErrorCode;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class UserDataToolsTest {

    private static final Long USER_ID = 1L;
    private static final ToolContext LOGGED_IN = new ToolContext(Map.of(UserDataTools.USER_ID, USER_ID));

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private UsageService usageService;

    @Mock
    private BillingService billingService;

    private UserDataTools tools;

    @BeforeEach
    void setUp() {
        tools = new UserDataTools(subscriptionService, usageService, billingService, CLOCK);
    }

    @Test
    void 사용자_ID가_없으면_조회하지_않고_로그인을_안내한다() {
        String result = tools.getUsageByMonth("2026-09", null, new ToolContext(Map.of("question", "지난달 사용량 알려줘")));

        assertThat(result).isEqualTo(UserDataTools.LOGIN_REQUIRED_MESSAGE);
        verifyNoInteractions(subscriptionService, usageService, billingService);
    }

    @Test
    void 회선이_하나면_번호_없이도_그_회선의_지난_월_사용량을_조회한다() {
        givenSubscriptions();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, UserDataTools.LOOKUP_MONTHS))
                .thenReturn(page(List.of(usage(YearMonth.of(2026, 10)), usage(YearMonth.of(2026, 9)))));

        String result = tools.getUsageByMonth("2026-09", null, LOGGED_IN);

        assertThat(result).startsWith("010-****-5678 회선\n2026-09 사용량(").contains("데이터 12.3GB 사용");
    }

    @Test
    void 지난_청구서를_조회하고_기록이_없으면_그_사실만_알린다() {
        givenSubscriptions();
        when(billingService.getMyBillingRecordList(USER_ID, 1L, null, 0, UserDataTools.LOOKUP_MONTHS))
                .thenReturn(page(List.of(
                        billing(YearMonth.of(2026, 10), PaymentStatus.UNPAID),
                        billing(YearMonth.of(2026, 9), PaymentStatus.PAID))));

        assertThat(tools.getBillingByMonth("2026-9", null, LOGGED_IN))
                .contains("2026년 9월 청구서(8월 사용분): 총 78,000원", "납부 완료(2026-09-20, 자동이체)")
                // 고객이 다른 달로 읽었으면 정정할 수 있게, 어느 청구서 기준인지 답변 첫머리에 밝히게 합니다.
                .endsWith("\n답변은 \"2026년 9월 청구서(8월 사용분) 기준으로 안내드립니다.\"로 시작하세요.");
        assertThat(tools.getBillingByMonth("2026-08", null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n2026-08 청구서 기록이 없습니다.");
    }

    @Test
    void 요약에_들어간_가장_최근_달은_다시_돌려주지_않는다() {
        givenSubscriptions();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, UserDataTools.LOOKUP_MONTHS))
                .thenReturn(page(List.of(usage(YearMonth.of(2026, 10)))));
        // 월초라 이번 달 청구서가 아직 없으면 요약에는 지난달 청구서가 들어가므로 지난달을 막습니다.
        when(billingService.getMyBillingRecordList(USER_ID, 1L, null, 0, UserDataTools.LOOKUP_MONTHS))
                .thenReturn(page(List.of(billing(YearMonth.of(2026, 9), PaymentStatus.UNPAID))));

        assertThat(tools.getUsageByMonth("2026-10", null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n" + String.format(UserDataTools.ALREADY_PROVIDED_MESSAGE, "2026-10", "사용량"));
        assertThat(tools.getBillingByMonth("2026-09", null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n" + String.format(UserDataTools.ALREADY_PROVIDED_MESSAGE, "2026-09", "청구서"));
        assertThat(tools.getBillingByMonth("2026-10", null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n2026-10 청구서 기록이 없습니다.");
    }

    @Test
    void 회선이_여러_개인데_번호가_없으면_추측하지_않고_되묻게_한다() {
        givenSubscriptions(
                subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE),
                subscription(2L, "010-9999-0001", SubscriptionStatus.SUSPENDED),
                subscription(3L, "010-8888-0002", SubscriptionStatus.TERMINATED));

        String result = tools.getBillingByMonth("2026-09", null, LOGGED_IN);

        assertThat(result)
                .startsWith("어느 회선인지 정할 수 없습니다.")
                .contains("- 010-****-5678 (이용 중", "- 010-****-0001 (일시정지")
                .doesNotContain("0002", "1234-5678");
        verify(subscriptionService).getMySubscriptionList(USER_ID);
    }

    @Test
    void 끝_4자리로_회선을_고르고_없으면_회선_목록을_보여_준다() {
        givenSubscriptions(
                subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE),
                subscription(2L, "010-9999-0001", SubscriptionStatus.ACTIVE));
        when(usageService.getMyUsageRecordList(USER_ID, 2L, null, 0, UserDataTools.LOOKUP_MONTHS))
                .thenReturn(page(List.of()));

        assertThat(tools.getUsageByMonth("2026-09", "0001", LOGGED_IN))
                .isEqualTo("010-****-0001 회선\n2026-09 사용량 기록이 없습니다.");
        assertThat(tools.getUsageByMonth("2026-09", "4321", LOGGED_IN))
                .startsWith("끝자리가 4321인 회선이 없습니다.")
                .contains("010-****-5678", "010-****-0001");
    }

    @Test
    void 해지_회선만_있으면_이용_중인_회선이_없다고_알린다() {
        givenSubscriptions(subscription(1L, "010-1234-5678", SubscriptionStatus.TERMINATED));

        assertThat(tools.getUsageByMonth("2026-09", null, LOGGED_IN)).isEqualTo(UserDataTools.NO_SUBSCRIPTION_MESSAGE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"지난달", "2026/09", "2026-13"})
    void 잘못된_월_형식은_조회하지_않고_다시_호출하게_한다(String month) {
        assertThat(tools.getUsageByMonth(month, null, LOGGED_IN)).contains("YYYY-MM 형식으로 넣어 다시 호출하세요.");
        verifyNoInteractions(subscriptionService, usageService);
    }

    @Test
    void 미래_월과_12개월보다_이전_월은_조회하지_않는다() {
        assertThat(tools.getBillingByMonth("2026-11", null, LOGGED_IN))
                .isEqualTo("2026-11은(는) 아직 오지 않은 달이라 기록이 없습니다. 조회 기준일은 2026-10-12입니다.");
        assertThat(tools.getBillingByMonth("2025-10", null, LOGGED_IN))
                .isEqualTo("최근 12개월(2025-11~2026-10) 기록만 조회할 수 있습니다.");
        verifyNoInteractions(subscriptionService, billingService);
    }

    @Test
    void 조회_중_예외가_나면_안내_문구로_돌려준다() {
        givenSubscriptions();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, UserDataTools.LOOKUP_MONTHS))
                .thenThrow(new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_FOUND));

        assertThat(tools.getUsageByMonth("2026-10", null, LOGGED_IN)).isEqualTo(UserDataTools.LOOKUP_FAILED_MESSAGE);
    }

    private void givenSubscriptions() {
        givenSubscriptions(subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE));
    }

    private void givenSubscriptions(SubscriptionResponseDto... subscriptions) {
        when(subscriptionService.getMySubscriptionList(USER_ID)).thenReturn(List.of(subscriptions));
    }
}
