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
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionBundleResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.enums.SubscriptionAddonStatus;
import com.ubot.subscription.enums.SubscriptionBundleStatus;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.exception.SubscriptionErrorCode;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        String result = tools.getAddons(null, new ToolContext(Map.of("question", "부가서비스 알려줘")));

        assertThat(result).isEqualTo(UserDataTools.LOGIN_REQUIRED_MESSAGE);
        verifyNoInteractions(subscriptionService, usageService, billingService);
    }

    @Test
    void 회선이_하나면_번호_없이도_그_회선의_월_사용량을_조회한다() {
        givenSubscriptions();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, YearMonth.of(2026, 9), 0, 1))
                .thenReturn(page(List.of(usage(YearMonth.of(2026, 9)))));

        String result = tools.getUsageByMonth("2026-09", null, LOGGED_IN);

        assertThat(result).startsWith("010-****-5678 회선\n2026-09 사용량(").contains("데이터 12.3GB 사용");
    }

    @Test
    void 월_청구서를_조회하고_기록이_없으면_그_사실만_알린다() {
        givenSubscriptions();
        when(billingService.getMyBillingRecordList(USER_ID, 1L, YearMonth.of(2026, 9), 0, 1))
                .thenReturn(page(List.of(billing(YearMonth.of(2026, 9), PaymentStatus.PAID))));
        when(billingService.getMyBillingRecordList(USER_ID, 1L, YearMonth.of(2026, 8), 0, 1))
                .thenReturn(page(List.of()));

        assertThat(tools.getBillingByMonth("2026-9", null, LOGGED_IN))
                .contains("2026-09 청구분(전월 사용분): 총 78,000원", "납부 완료(2026-09-20, 자동이체)");
        assertThat(tools.getBillingByMonth("2026-08", null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n2026-08 청구서 기록이 없습니다.");
    }

    @Test
    void 회선이_여러_개인데_번호가_없으면_추측하지_않고_되묻게_한다() {
        givenSubscriptions(
                subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE),
                subscription(2L, "010-9999-0001", SubscriptionStatus.SUSPENDED),
                subscription(3L, "010-8888-0002", SubscriptionStatus.TERMINATED));

        String result = tools.getAddons(null, LOGGED_IN);

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
        when(subscriptionService.getMyAddonList(USER_ID, 2L)).thenReturn(List.of());

        assertThat(tools.getAddons("0001", LOGGED_IN)).isEqualTo("010-****-0001 회선\n이용 중인 부가서비스가 없습니다.");
        assertThat(tools.getAddons("4321", LOGGED_IN))
                .startsWith("끝자리가 4321인 회선이 없습니다.")
                .contains("010-****-5678", "010-****-0001");
    }

    @Test
    void 해지_회선만_있으면_이용_중인_회선이_없다고_알린다() {
        givenSubscriptions(subscription(1L, "010-1234-5678", SubscriptionStatus.TERMINATED));

        assertThat(tools.getBundles(null, LOGGED_IN)).isEqualTo(UserDataTools.NO_SUBSCRIPTION_MESSAGE);
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
        when(usageService.getMyUsageRecordList(USER_ID, 1L, YearMonth.of(2026, 10), 0, 1))
                .thenThrow(new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_FOUND));

        assertThat(tools.getUsageByMonth("2026-10", null, LOGGED_IN)).isEqualTo(UserDataTools.LOOKUP_FAILED_MESSAGE);
    }

    @Test
    void 부가서비스와_결합은_이용_중인_것만_보여_준다() {
        givenSubscriptions();
        LocalDateTime joined = LocalDateTime.of(2025, 1, 2, 9, 0);
        when(subscriptionService.getMyAddonList(USER_ID, 1L)).thenReturn(List.of(
                new SubscriptionAddonResponseDto(1L, 10L, "V컬러링", 3300, SubscriptionAddonStatus.ACTIVE, joined, null),
                new SubscriptionAddonResponseDto(2L, 11L, "해지된 서비스", 1100, SubscriptionAddonStatus.CANCELED, joined, joined)));
        when(subscriptionService.getMyBundleList(USER_ID, 1L)).thenReturn(List.of(
                new SubscriptionBundleResponseDto(1L, 20L, "인터넷 결합", 5500, SubscriptionBundleStatus.ACTIVE, joined, null),
                new SubscriptionBundleResponseDto(2L, 21L, "끝난 결합", 1000, SubscriptionBundleStatus.ENDED, joined, joined)));

        assertThat(tools.getAddons(null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n- V컬러링 | 월 3,300원 | 2025-01-02 가입");
        assertThat(tools.getBundles(null, LOGGED_IN))
                .isEqualTo("010-****-5678 회선\n- 인터넷 결합 | 할인 5,500원 | 2025-01-02 가입");
    }

    @Test
    void 로밍은_이용_중이거나_예정인_것만_보여_준다() {
        givenSubscriptions();
        when(subscriptionService.getMyRoamingList(USER_ID, 1L)).thenReturn(List.of(
                roaming("일본 데이터 로밍", SubscriptionRoamingStatus.SCHEDULED),
                roaming("지난 로밍", SubscriptionRoamingStatus.EXPIRED)));

        assertThat(tools.getRoamings(null, LOGGED_IN)).isEqualTo("010-****-5678 회선\n"
                + "- 일본 데이터 로밍 | 이용 예정 | 일본 | 2026-10-20~2026-10-24 | 일 11,000원 | 데이터 3.0GB");
    }

    private SubscriptionRoamingResponseDto roaming(String name, SubscriptionRoamingStatus status) {
        return new SubscriptionRoamingResponseDto(1L, 30L, name, "일본", 11000, 3072L,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 24), status, null);
    }

    private void givenSubscriptions() {
        givenSubscriptions(subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE));
    }

    private void givenSubscriptions(SubscriptionResponseDto... subscriptions) {
        when(subscriptionService.getMySubscriptionList(USER_ID)).thenReturn(List.of(subscriptions));
    }
}
