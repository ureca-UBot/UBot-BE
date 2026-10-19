package com.ubot.ai.tool;

import static com.ubot.ai.tool.UserDataFixtures.CLOCK;
import static com.ubot.ai.tool.UserDataFixtures.SIM_IDENTIFIER;
import static com.ubot.ai.tool.UserDataFixtures.billing;
import static com.ubot.ai.tool.UserDataFixtures.contract;
import static com.ubot.ai.tool.UserDataFixtures.page;
import static com.ubot.ai.tool.UserDataFixtures.plan;
import static com.ubot.ai.tool.UserDataFixtures.subscription;
import static com.ubot.ai.tool.UserDataFixtures.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.UserDataSummary;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.billing.service.BillingService;
import com.ubot.contract.service.ContractService;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionBundleResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.enums.SubscriptionAddonStatus;
import com.ubot.subscription.enums.SubscriptionBundleStatus;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.enums.UserRole;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserDataSummarizerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private UserService userService;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private UsageService usageService;

    @Mock
    private BillingService billingService;

    @Mock
    private ContractService contractService;

    private UserDataSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new UserDataSummarizer(
                userService, subscriptionService, usageService, billingService, contractService, CLOCK);
    }

    @Test
    void 프로필은_기준일의_만_나이로_계산하고_이메일과_생년월일은_담지_않는다() {
        // 생일(10월 13일) 하루 전이라 아직 25세입니다.
        givenUser(LocalDate.of(2000, 10, 13));
        when(subscriptionService.getMySubscriptionList(USER_ID)).thenReturn(List.of());

        UserDataSummary summary = summarizer.summarize(USER_ID);

        assertThat(summary.baseDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(summary.profile()).isEqualTo(
                new UserDataSummary.Profile("홍길동", 25, "서울 강남구", LocalDate.of(2026, 3, 2)));
        assertThat(summarizer.format(summary))
                .startsWith("조회 기준일: 2026-10-12\n이름: 홍길동 | 나이: 만 25세 | 거주 지역: 서울 강남구 | 회원 가입일: 2026-03-02")
                .contains(UserDataSummarizer.NO_SUBSCRIPTION_MESSAGE)
                .doesNotContain("user@example.com", "2000-10-13");
    }

    @Test
    void 생일이_지났으면_나이를_올리고_생년월일이_없으면_정보_없음으로_표시한다() {
        givenUser(LocalDate.of(2000, 10, 12));
        when(subscriptionService.getMySubscriptionList(USER_ID)).thenReturn(List.of());
        assertThat(summarizer.summarize(USER_ID).profile().age()).isEqualTo(26);

        givenUser(null);
        UserDataSummary summary = summarizer.summarize(USER_ID);
        assertThat(summary.profile().age()).isNull();
        assertThat(summarizer.format(summary)).contains("나이: 정보 없음");
    }

    @Test
    void 사용자가_없으면_예외를_그대로_던진다() {
        when(userService.getActiveUser(USER_ID)).thenThrow(new UserException(UserErrorCode.USER_NOT_FOUND));

        assertThatThrownBy(() -> summarizer.summarize(USER_ID)).isInstanceOf(UserException.class);
    }

    @Test
    void 해지_회선은_빼고_이용_중인_회선을_최근_개통_순으로_모두_조회한다() {
        givenUser(LocalDate.of(2000, 1, 1));
        List<SubscriptionResponseDto> subscriptions = List.of(
                subscription(1L, "010-1111-1111", SubscriptionStatus.ACTIVE),
                subscription(2L, "010-2222-2222", SubscriptionStatus.TERMINATED),
                subscription(3L, "010-3333-3333", SubscriptionStatus.SUSPENDED),
                subscription(4L, "010-4444-4444", SubscriptionStatus.ACTIVE),
                subscription(5L, "010-5555-5555", SubscriptionStatus.ACTIVE));
        when(subscriptionService.getMySubscriptionList(USER_ID)).thenReturn(subscriptions);
        when(subscriptionService.getMyPlan(eq(USER_ID), anyLong())).thenReturn(plan());
        when(usageService.getMyUsageRecordList(eq(USER_ID), anyLong(), isNull(), eq(0), eq(1))).thenReturn(page(List.of()));
        when(billingService.getMyBillingRecordList(eq(USER_ID), anyLong(), isNull(), eq(0), eq(6))).thenReturn(page(List.of()));

        UserDataSummary summary = summarizer.summarize(USER_ID);

        assertThat(summary.subscriptions()).extracting(s -> s.subscription().subscriptionId())
                .containsExactly(1L, 3L, 4L, 5L);
        verify(subscriptionService, never()).getMyPlan(USER_ID, 2L);
        assertThat(summarizer.format(summary))
                .contains("이용 중인 회선: 4개", "[회선 1] 010-****-1111 (이용 중", "[회선 2] 010-****-3333 (일시정지",
                        "[회선 4] 010-****-5555 (이용 중",
                        "최근 사용량: 기록 없음", "최근 청구서(이번 달 요금·청구 금액·납부 상태): 기록 없음",
                        "미납: 없음(최근 6개월 청구 기준)", "이용 중인 약정 없음")
                // 청구서가 없으면 기준을 밝히라는 문장도 넣지 않습니다.
                .doesNotContain("010-2222", "2222-2222", UserDataSummarizer.BILLING_BASIS_NOTE);
    }

    @Test
    void 최근_청구_6건_중_미납과_연체를_모으고_가장_최근_청구를_따로_담는다() {
        givenSingleSubscription();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, 1)).thenReturn(page(List.of()));
        List<BillingRecordResponseDto> billings = List.of(
                billing(YearMonth.of(2026, 10), PaymentStatus.UNPAID),
                billing(YearMonth.of(2026, 9), PaymentStatus.OVERDUE),
                billing(YearMonth.of(2026, 8), PaymentStatus.PAID));
        when(billingService.getMyBillingRecordList(USER_ID, 1L, null, 0, 6)).thenReturn(page(billings));

        UserDataSummary.SubscriptionSummary summary = summarizer.summarize(USER_ID).subscriptions().getFirst();

        assertThat(summary.latestBilling()).isSameAs(billings.getFirst());
        assertThat(summary.unpaidBillings()).extracting(BillingRecordResponseDto::billingMonth)
                .containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 9));
    }

    @Test
    void 회선별_요금제_사용량_청구_약정을_읽기_쉬운_문장으로_정리한다() {
        givenSingleSubscription();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, 1))
                .thenReturn(page(List.of(usage(YearMonth.of(2026, 10)))));
        when(billingService.getMyBillingRecordList(USER_ID, 1L, null, 0, 6)).thenReturn(page(List.of(
                billing(YearMonth.of(2026, 10), PaymentStatus.UNPAID),
                billing(YearMonth.of(2026, 9), PaymentStatus.PAID))));
        when(contractService.getMyContract(USER_ID, 1L)).thenReturn(contract());

        String text = summarizer.format(summarizer.summarize(USER_ID));

        assertThat(text)
                .contains("[회선 1] 010-****-5678 (이용 중, 2024-03-02 개통, 요금제 5G 프리미어)")
                .contains("- 요금제: 5G 스탠다드 | 월정액 75,000원 | 데이터 150.0GB(소진 후 최대 5Mbps) | 음성 무제한 | 문자 무제한")
                .contains("- 최근 사용량: 2026-10 사용량(2026-10-11까지 집계, 미확정): 데이터 12.3GB 사용, 잔여 137.7GB"
                        + " | 음성 120분 사용, 잔여 무제한 | 문자 3건 사용, 잔여 무제한")
                // 이번 달 요금을 묻는 여러 표현이 이 줄로 답해지도록 라벨에 붙입니다.
                .contains("- 최근 청구서(이번 달 요금·청구 금액·납부 상태): 2026년 10월 청구서(9월 사용분) 기준: 총 78,000원"
                        + "(기본료 75,000원, 부가서비스 3,000원, 로밍 0원, 기타 0원, 할인 -0원)"
                        + " | 납부 기한 2026-10-25 | 미납. " + UserDataSummarizer.BILLING_BASIS_NOTE)
                .contains("- 미납: 2026년 10월 청구서 미납액 78,000원(납부 기한 2026-10-25, 미납)")
                .contains("- 약정·할부: 선택약정 2024-03-02~2026-03-01 | 약정 할인 18,750원"
                        + " | 단말 할부: iPhone 15, 월 50,000원, 24개월 중 19개월 납부, 잔여 250,000원, 만료 2026-03-01")
                .contains("이 자료에는 가장 최근 달(사용량 2026-10, 2026년 10월 청구서)만 있습니다. " + UserDataSummarizer.TOOL_GUIDE)
                // 금지형 안내는 작은 모델이 도구를 아예 호출하지 않게 만들고,
                // "이번 달 요금은 ~ 답하세요" 같은 답변 지시문은 도구가 필요한 질문에서도 바로 답하게 만듭니다.
                .doesNotContain("조회하지 마세요", "'이번 달 요금'은")
                .endsWith(UserDataSummarizer.UNAVAILABLE_NOTICE)
                .doesNotContain("010-1234-5678", "01012345678", SIM_IDENTIFIER, "USIM");
    }

    @Test
    void 부가서비스와_결합은_이용_중인_것만_로밍은_이용_중이거나_예약된_것만_담는다() {
        givenSingleSubscription();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, 1)).thenReturn(page(List.of()));
        when(billingService.getMyBillingRecordList(USER_ID, 1L, null, 0, 6)).thenReturn(page(List.of()));
        LocalDateTime joined = LocalDateTime.of(2025, 1, 2, 9, 0);
        when(subscriptionService.getMyAddonList(USER_ID, 1L)).thenReturn(List.of(
                new SubscriptionAddonResponseDto(1L, 10L, "V컬러링", 3300, SubscriptionAddonStatus.ACTIVE, joined, null),
                new SubscriptionAddonResponseDto(2L, 11L, "지니뮤직", 7900, SubscriptionAddonStatus.ACTIVE, joined, null),
                new SubscriptionAddonResponseDto(3L, 12L, "해지된 서비스", 1100, SubscriptionAddonStatus.CANCELED, joined, joined)));
        when(subscriptionService.getMyBundleList(USER_ID, 1L)).thenReturn(List.of(
                new SubscriptionBundleResponseDto(1L, 20L, "인터넷 결합", 5500, SubscriptionBundleStatus.ACTIVE, joined, null),
                new SubscriptionBundleResponseDto(2L, 21L, "끝난 결합", 1000, SubscriptionBundleStatus.ENDED, joined, joined)));
        when(subscriptionService.getMyRoamingList(USER_ID, 1L)).thenReturn(List.of(
                roaming("일본 데이터 로밍", SubscriptionRoamingStatus.SCHEDULED),
                roaming("지난 로밍", SubscriptionRoamingStatus.EXPIRED)));

        UserDataSummary summary = summarizer.summarize(USER_ID);

        assertThat(summarizer.format(summary))
                .contains("- 약정·할부: 이용 중인 약정 없음\n"
                        + "- 부가서비스(이용 중): V컬러링 월 3,300원(2025-01-02 가입), 지니뮤직 월 7,900원(2025-01-02 가입)\n"
                        + "- 결합 상품(이용 중): 인터넷 결합 할인 5,500원(2025-01-02 가입)\n"
                        + "- 로밍(이용 중·예약): 일본 데이터 로밍 | 이용 예정 | 일본 | 2026-10-20~2026-10-24 | 일 11,000원 | 데이터 3.0GB\n")
                .doesNotContain("해지된 서비스", "끝난 결합", "지난 로밍");
    }

    @Test
    void 부가서비스_결합_로밍이_없어도_없음으로_적는다() {
        givenSingleSubscription();
        when(usageService.getMyUsageRecordList(USER_ID, 1L, null, 0, 1)).thenReturn(page(List.of()));
        when(billingService.getMyBillingRecordList(USER_ID, 1L, null, 0, 6)).thenReturn(page(List.of()));

        // 목록 줄이 아예 없으면 청구서의 부가서비스 요금이나 요금제 이름으로 목록을 지어냅니다.
        assertThat(summarizer.format(summarizer.summarize(USER_ID)))
                .contains("- 부가서비스(이용 중): 없음\n", "- 결합 상품(이용 중): 없음\n", "- 로밍(이용 중·예약): 없음\n");
    }

    @Test
    void 전화번호는_끝_4자리만_남긴다() {
        assertThat(UserDataSummarizer.maskPhone("010-1234-5678")).isEqualTo("010-****-5678");
        assertThat(UserDataSummarizer.maskPhone("01012345678")).isEqualTo("010-****-5678");
        assertThat(UserDataSummarizer.maskPhone("011-123-4567")).isEqualTo("011-***-4567");
        assertThat(UserDataSummarizer.maskPhone("+82 10 1234 5678")).isEqualTo("********5678");
        assertThat(UserDataSummarizer.maskPhone("123")).isEqualTo("****");
        assertThat(UserDataSummarizer.maskPhone(null)).isEqualTo("****");
    }

    private SubscriptionRoamingResponseDto roaming(String name, SubscriptionRoamingStatus status) {
        return new SubscriptionRoamingResponseDto(1L, 30L, name, "일본", 11000, 3072L,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 24), status, null);
    }

    private void givenSingleSubscription() {
        givenUser(LocalDate.of(2000, 1, 1));
        when(subscriptionService.getMySubscriptionList(USER_ID))
                .thenReturn(List.of(subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE)));
        when(subscriptionService.getMyPlan(USER_ID, 1L)).thenReturn(plan());
    }

    private void givenUser(LocalDate birthDate) {
        when(userService.getActiveUser(USER_ID)).thenReturn(new UserResponseDto(USER_ID, "user@example.com", "홍길동",
                birthDate, null, "서울 강남구", UserRole.USER, LocalDateTime.of(2026, 3, 2, 10, 0)));
    }
}
