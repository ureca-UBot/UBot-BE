package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentMethod;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.contract.dto.response.InstallmentResponseDto;
import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.ContractType;
import com.ubot.contract.enums.InstallmentStatus;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.plan.dto.response.PlanSummaryResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.enums.SimStatus;
import com.ubot.subscription.enums.SimType;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.usage.dto.response.UsageAllowanceResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 제공 근거의 정상 안내와 조건·항목을 바꾼 오답을 짝지어 검사합니다. 외부 서비스는 쓰지 않습니다. */
class AiOutputGuardEvidenceRegressionTest {

    private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
    private final ForbiddenWordFilterService words = new ForbiddenWordFilterService(repository);
    private final AiOutputGuardService guard = new AiOutputGuardService(words);
    private static final String FAMILY = "2회선이면 대표 회선에서 월 6,000원, 3회선이면 월 15,000원, "
            + "4회선이면 월 20,000원, 5회선이면 월 24,000원을 할인합니다.";
    private static final String WATCH = "워치 요금제는 0.5GB(월 5,500원), 1GB(8,800원), 2GB(11,000원)입니다.";

    @BeforeEach
    void initializeMemoryOnly() {
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of());
        words.refreshForbiddenWordCache();
    }

    @ParameterizedTest
    @CsvSource({"2,6000", "3,15000", "4,20000", "5,24000"})
    void preservesEveryConditionValuePair(int lines, int discount) {
        preserves("가족 결합 " + lines + "회선은 월 " + discount + "원을 할인합니다.",
                source("가족 결합 할인은 얼마인가요?", FAMILY));
    }

    @ParameterizedTest
    @ValueSource(ints = {15000, 20000, 24000, 99000})
    void cannotBorrowAnotherLinesDiscount(int discount) {
        rejects("가족 결합 2회선은 월 " + discount + "원을 할인합니다.",
                source("가족 결합 2회선 할인은 얼마인가요?", FAMILY));
    }

    @Test
    void cannotInventAnotherConditionFromTheSameList() {
        rejects("가족 결합 6회선은 월 24,000원을 할인합니다.", source("가족 결합 할인은요?", FAMILY));
    }

    @Test
    void preservesEligibilityAlongsideTheAmount() {
        var evidence = source("가족 결합 할인은요?", "가족 결합은 성인 월 6,000원, 미성년자 월 3,000원을 할인합니다.");
        preserves("가족 결합은 성인 월 6,000원을 할인합니다.", evidence);
        preserves("가족 결합은 미성년자 월 3,000원을 할인합니다.", evidence);
        rejects("가족 결합은 성인 월 3,000원을 할인합니다.", evidence);
        rejects("가족 결합은 법인 월 6,000원을 할인합니다.", evidence);
    }

    @Test
    void fillsOnlySingleExplicitQuestionCondition() {
        preserves("월 할인 금액은 6,000원입니다.", source("가족 결합 2회선 할인은 얼마인가요?", FAMILY));
        rejects("월 할인 금액은 24,000원입니다.", source("가족 결합 2회선 할인은 얼마인가요?", FAMILY));
    }

    @ParameterizedTest
    @CsvSource({"0.5,5500", "1,8800", "2,11000"})
    void preservesDataTierPrices(String gb, int price) {
        preserves("워치 " + gb + "GB 요금제의 월 요금은 " + price + "원입니다.",
                source("워치 요금제의 요금은 얼마인가요?", WATCH));
    }

    @Test
    void checksUnlabelledPriceAgainstItsTier() {
        var evidence = source("워치 1GB 요금제의 요금은 얼마인가요?", WATCH);
        preserves("1GB는 8,800원입니다.", evidence);
        rejects("1GB는 5,500원입니다.", evidence);
        rejects("워치 1GB 요금제의 월 요금은 5,500원입니다.", evidence);
    }

    @Test
    void comparesOnlyTheNamedProduct() {
        var evidence = AnswerMaterials.builder("비디오패스 월 요금은 얼마인가요?").addFaqs(List.of(
                faq(42, "비디오패스 요금은요?", "비디오패스는 월 9,900원입니다."),
                faq(57, "IPTV 요금은요?", "IPTV 월 요금은 16,500원입니다."))).build();
        preserves("비디오패스 월 요금은 9,900원입니다.", evidence);
        rejects("비디오패스 월 요금은 16,500원입니다.", evidence);
    }

    @Test
    void keepsInternetSpeedAndFamilyCountSeparate() {
        var evidence = AnswerMaterials.builder("인터넷 결합 500Mbps 기준 할인은요?").addFaqs(List.of(
                faq(34, "인터넷 결합 할인은요?", "인터넷 결합은 500Mbps 기준 월 5,500원을 할인합니다."),
                faq(72, "가족 결합 할인은요?", FAMILY))).build();
        preserves("인터넷 결합은 500Mbps 기준 월 5,500원을 할인합니다.", evidence);
        rejects("인터넷 결합은 500Mbps 기준 월 24,000원을 할인합니다.", evidence);
    }

    @Test
    void explanatoryModifierIsNotANewRestriction() {
        var evidence = source("태블릿 요금제는 음성과 문자를 제공하나요?",
                "태블릿 요금제는 5GB 월 9,900원부터 20GB 월 19,900원까지 있으며 음성·문자는 제공하지 않습니다.");
        preserves("태블릿 요금제는 데이터 전용 상품으로 음성·문자는 제공하지 않습니다.", evidence);
        rejects("워치 요금제는 데이터 전용 상품으로 음성·문자는 제공하지 않습니다.", evidence);
        rejects("미성년자는 태블릿 요금제를 이용할 수 없습니다.", evidence);
    }

    @Test
    void resolvesAnExplicitRestrictionSubjectOnly() {
        var evidence = source("엠세이퍼 가입 제한이 걸려 있으면 본인도 개통할 수 없나요?",
                "엠세이퍼 가입 제한이 걸려 있으면 본인도 새 휴대폰을 개통할 수 없습니다.");
        preserves("이 제한이 걸려 있으면 본인도 새 휴대폰을 개통할 수 없습니다.", evidence);
        rejects("본인도 새 휴대폰을 개통할 수 없습니다.", evidence);
        rejects("다른 서비스 제한이 걸려 있으면 본인도 새 휴대폰을 개통할 수 없습니다.", evidence);
    }

    @Test
    void preservesOnlyTheEquivalentTimeRestriction() {
        var evidence = source("방문 시간이 지난 후 취소할 수 있나요?",
                "방문 예약은 방문 시간 전까지만 취소할 수 있습니다.");
        preserves("방문 시간이 지나면 방문 예약을 취소할 수 없습니다.", evidence);
        rejects("방문 예약은 취소할 수 없습니다.", evidence);
        rejects("방문 시간 전에는 방문 예약을 취소할 수 없습니다.", evidence);
        rejects("방문 시간이 지나면 요금제를 변경할 수 없습니다.", evidence);
    }

    @Test
    void doesNotTurnAPermissionIntoAnExclusiveLimit() {
        rejects("방문 시간이 지나면 방문 예약을 취소할 수 없습니다.", source("방문 예약 취소는요?",
                "방문 예약은 방문 시간 전에 취소할 수 있습니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "예시회원은 2024-03-15에 가입했습니다.",
            "2024년 3월 15일에 회원 가입했으며 현재 가입 정보는 조회된 기록입니다.",
            "24개월 중 19개월 납부했고 5개월 남았습니다.",
            "고객님의 할부 월 납부액은 48,125원입니다."
    })
    void preservesVerifiedMemberHistory(String answer) {
        preserves(answer, member("제 가입 정보와 할부 내역을 알려주세요.", PaymentStatus.PAID));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "예시회원은 2024-03-16에 가입했습니다.",
            "제가 2024-03-15에 회원 가입했습니다.",
            "친구는 2024-03-15에 회원 가입했습니다.",
            "2024-03-15에 가입해 드렸습니다.",
            "24개월 중 18개월 납부했고 6개월 남았습니다.",
            "24개월 중 19개월 납부했고 6개월 남았습니다.",
            "36개월 중 19개월 납부했습니다.",
            "고객님의 할부 월 납부액은 75,000원입니다.",
            "2024-03-15에 가입했고 환불도 완료했습니다."
    })
    void keepsWrongHistoryAndNewExecutionBlocked(String answer) {
        rejects(answer, member("제 가입 정보와 할부 내역을 알려주세요.", PaymentStatus.PAID));
    }

    @Test
    void distinguishesPaidRecordFromUnverifiedCompletion() {
        String answer = "10월 청구서 납부가 자동이체로 완료되었습니다.";
        preserves(answer, member("10월 청구서 납부 상태는요?", PaymentStatus.PAID));
        rejects(answer, member("10월 청구서 납부 상태는요?", PaymentStatus.UNPAID));
        rejects("9월 청구서 납부가 자동이체로 완료되었습니다.", member("10월 납부 상태는요?", PaymentStatus.PAID));
        rejects("제가 10월 청구서 납부를 완료했습니다.", member("10월 납부 상태는요?", PaymentStatus.PAID));
        rejects("친구의 10월 청구서 납부가 자동이체로 완료되었습니다.", member("10월 납부 상태는요?", PaymentStatus.PAID));
        rejects("10월 청구서 납부가 카드로 완료되었습니다.", member("10월 납부 상태는요?", PaymentStatus.PAID));
        rejects("2026년 10월 23일에 10월 청구서 납부가 완료되었습니다.", member("10월 납부 상태는요?", PaymentStatus.PAID));
    }

    @Test
    void planNameNumberIsNotTheAllowanceClaim() {
        var evidence = member("제 요금제와 데이터 제공량은요?", PaymentStatus.PAID);
        preserves("요금제는 밸런스 59 75GB업이며 데이터 제공량은 96.7GB입니다.", evidence);
        rejects("요금제는 밸런스 59 75GB업이며 기본 데이터 제공량은 75GB입니다.", evidence);
    }

    @Test
    void wholeAnswerAndFollowupPoliciesStayUnchanged() {
        rejects("요금제는 앱에서 변경할 수 있습니다. 정확한 요금제 이름을 알려주실 수 있나요?",
                source("요금제 변경은요?", "요금제는 앱에서 변경할 수 있습니다."));
        var result = guard.inspectAnswer("요금제는 앱에서 변경할 수 있습니다. 제가 가입을 완료했습니다.",
                source("요금제 변경은요?", "요금제는 앱에서 변경할 수 있습니다."), null);
        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
    }

    @Test
    void storeLocationInputStillRequiresServerToolContext() {
        var evidence = AnswerMaterials.builder("근처 매장과 월 요금 안내")
                .addFaqs(List.of(faq(1, "월 요금", "월 요금은 55,000원입니다.")))
                .enableTool(com.ubot.ai.tool.AiTool.STORE_SEARCH).build();
        preserves("위치를 알려 주세요.", evidence);
        rejects("위치를 알려 주세요. 월 요금은 99,000원입니다.", evidence);
        rejects("이름을 알려 주세요.", evidence);
        rejects("위치를 알려 주세요.", source("근처 매장은요?", "월 요금은 55,000원입니다."));
    }

    private static FaqSearchResponseDto faq(long id, String question, String answer) {
        return new FaqSearchResponseDto(id, question, answer, 0.95, Intent.GENERAL);
    }

    private static AnswerMaterials source(String question, String answer) {
        return AnswerMaterials.builder(question).addFaqs(List.of(faq(99, question, answer))).build();
    }

    private static AnswerMaterials member(String question, PaymentStatus status) {
        var day = LocalDate.of(2026, 10, 24);
        var bill = new BillingRecordResponseDto(1L, YearMonth.of(2026, 10), 75000, 0, 0, 0, 0, 75000,
                day, status, status == PaymentStatus.PAID ? 75000 : 0,
                status == PaymentStatus.PAID ? PaymentMethod.AUTO_TRANSFER : null,
                status == PaymentStatus.PAID ? day.atStartOfDay() : null);
        var plan = new PlanDetailResponseDto(new PlanSummaryResponseDto(1L, "TEST", "밸런스 59 75GB업",
                null, null, 75000, 99021L, false, 5000, null, true, null, true), "설명", null, null, null);
        var contract = new SubscriptionContractResponseDto(1L, ContractType.SELECTIVE_CONTRACT,
                day.minusYears(1), day.plusYears(1), 18750, ContractStatus.ACTIVE, null,
                new InstallmentResponseDto("예시 단말", 1155000, 24, 19, 48125, 240625,
                        day.minusYears(1), day.plusYears(1), InstallmentStatus.ACTIVE));
        var subscription = new SubscriptionResponseDto(1L, "010-0000-1234", SubscriptionStatus.ACTIVE,
                LocalDateTime.of(2024, 3, 15, 10, 0), null, 1L, "밸런스 59 75GB업", SimType.USIM,
                "********1234", SimStatus.ACTIVE, day.atStartOfDay(), null);
        var usage = new UsageRecordResponseDto(1L, YearMonth.of(2026, 10), 0L, 0, 0,
                new UsageAllowanceResponseDto(99021L, false, null, true, null, true),
                99021L, null, null, day, false, null, null);
        var summary = new UserDataSummary(day, new UserDataSummary.Profile("예시회원", 30, "서울",
                LocalDate.of(2024, 3, 15)), List.of(new UserDataSummary.SubscriptionSummary(subscription,
                plan, usage, bill, List.of(), contract, List.of(), List.of(), List.of())));
        return AnswerMaterials.builder(question).userId(1L).userDataSummary(summary)
                .addSection(new ContextSection("사용자 정보", "이름: 예시회원\n회원 가입일: 2024-03-15\n"
                        + "요금제: 밸런스 59 75GB업\n데이터 제공량: 96.7GB\n월정액: 75,000원\n"
                        + "할부 월 납부액: 48,125원\n할부 기간: 24개월\n납부 개월 수: 19개월\n"
                        + "10월 청구서 납부 상태: " + status)).build();
    }

    private void preserves(String answer, AnswerMaterials evidence) {
        var result = guard.inspectAnswer(answer, evidence, null);
        assertThat(result.rejected()).as(answer).isFalse();
        assertThat(result.answer()).isEqualTo(answer);
    }

    private void rejects(String answer, AnswerMaterials evidence) {
        var result = guard.inspectAnswer(answer, evidence, null);
        assertThat(result.rejected()).as(answer).isTrue();
        assertThat(result.answer()).isNull();
    }
}
