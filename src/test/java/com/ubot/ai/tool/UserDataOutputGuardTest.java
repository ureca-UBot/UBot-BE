package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.service.AiOutputGuardService;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.billing.service.BillingService;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.contract.service.ContractService;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import com.ubot.user.service.UserService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 출력 가드가 회원의 실제 기록(가입 정보 요약·도구 조회 결과)으로 답한 정상 답변은 지우지 않고,
 * 기록에 없는 청구서를 단정하거나 다른 달·다른 항목의 값으로 바꿔 말한 답은 지우는지 확인합니다.
 * 요약에는 2026년 10월 청구서(총 78,000원, 기본료 75,000원, 부가서비스 3,000원, 미납)와 10월 사용량이 있습니다.
 */
class UserDataOutputGuardTest {

    private static final String BASIS = "2026년 10월 청구서(9월 사용분) 기준으로 안내드립니다.";

    private final AiOutputGuardService guard =
            new AiOutputGuardService(new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)));
    private final UserDataSummarizer summarizer = new UserDataSummarizer(mock(UserService.class),
            mock(SubscriptionService.class), mock(UsageService.class), mock(BillingService.class),
            mock(ContractService.class), UserDataFixtures.CLOCK);

    static Stream<String> summaryAnswers() {
        return Stream.of(
                BASIS + " 이번 달 요금은 총 78,000원입니다.",
                "이번 달 청구 금액은 78,000원이고 납부 기한은 2026-10-25입니다.",
                "고객님의 요금제는 5G 스탠다드이며 월정액은 75,000원입니다.",
                "이번 달 데이터는 " + UserDataSummarizer.megabytes(12595) + " 사용하셨고, "
                        + UserDataSummarizer.megabytes(141005) + " 남았습니다.",
                "고객님의 청구서에는 부가서비스 요금 3,000원이 포함되었습니다.",
                "고객님의 청구 내역을 확인한 결과, 10월 청구서 미납액은 78,000원입니다.",
                BASIS + " 기본료 75,000원에 부가서비스 3,000원이 더해져 총 78,000원입니다.");
    }

    @ParameterizedTest
    @MethodSource("summaryAnswers")
    void 가입_정보_요약으로_답한_정상_답변은_그대로_둔다(String answer) {
        assertThat(guard.filterAnswer(answer, materials(), null)).isEqualTo(answer);
    }

    @Test
    void 도구로_조회한_지난_달_청구서_금액도_근거로_본다() {
        String answer = "2026년 9월 청구서(8월 사용분) 기준으로 안내드립니다. 9월 청구서 요금은 총 61,950원입니다.";

        assertThat(guard.filterAnswer(answer, withSeptemberBill(materials()), null)).isEqualTo(answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10월 청구서 요금은 총 61,950원입니다.",   // 9월 청구서 금액을 10월로
            "9월 청구서 요금은 총 78,000원입니다.",    // 10월 청구서 금액을 9월로
            "이번 달 청구 금액은 75,000원입니다.",     // 월정액(기본료)을 청구 금액으로
            "이번 달 요금은 총 18,750원입니다.",       // 약정 할인액을 청구 금액으로
            "이번 달 요금은 총 50,000원입니다.",       // 단말 할부금을 청구 금액으로
            "이번 달 요금은 총 99,000원입니다."})      // 기록에 없는 금액
    void 다른_달이나_다른_항목의_금액으로_말한_청구_금액은_지운다(String wrong) {
        assertRejected(BASIS + " " + wrong, withSeptemberBill(materials()), null);
    }

    @Test
    void 사용량과_잔여량을_바꿔_말하면_지운다() {
        String answer = "이번 달 데이터는 " + UserDataSummarizer.megabytes(141005) + " 사용하셨고, "
                + UserDataSummarizer.megabytes(12595) + " 남았습니다.";

        assertRejected(() -> guard.filterAnswer(answer, materials(), null));
    }

    @Test
    void 요약이_실패해도_도구로_조회한_청구서로_답한_청구_내역은_그대로_둔다() {
        String answer = "고객님의 청구서에는 부가서비스 요금 11,200원이 포함되었습니다.";

        assertThat(guard.filterAnswer(answer, withSeptemberBill(lookupFailed()), null)).isEqualTo(answer);
    }

    @Test
    void 요약이_실패하고_도구도_조회하지_않았으면_청구_내역_단정은_거절한다() {
        assertRejected(() -> guard.filterAnswer("고객님의 청구서에는 부가서비스 요금 11,200원이 포함되었습니다.",
                lookupFailed(), null));
    }

    @Test
    void 요약에_청구서가_없으면_청구_내역_단정은_거절한다() {
        var profileOnly = new UserDataSummary(LocalDate.of(2026, 10, 12),
                new UserDataSummary.Profile("홍길동", 30, "서울", LocalDate.of(2024, 3, 2)), List.of());
        var materials = AnswerMaterials.builder("이번 달 청구서에 뭐 포함됐어?")
                .addSection(new ContextSection(UserIntentHandler.SECTION_TITLE, summarizer.format(profileOnly)))
                .userId(1L)
                .userDataSummary(profileOnly)
                .build();

        assertRejected(() -> guard.filterAnswer("고객님의 청구서에는 로밍 요금이 포함되었습니다.", materials, null));
    }

    @Test
    void 도구로_사용량만_조회했으면_청구_내역_단정은_거절한다() {
        var recorder = new UserDataRecorder();
        recorder.recordUsage("010-****-5678", UserDataFixtures.usage(YearMonth.of(2026, 9)));
        var materials = lookupFailed().withToolRecords(
                new ContextSection(UserDataRecorder.SECTION_TITLE, recorder.describe()), recorder.cards());

        assertRejected(() -> guard.filterAnswer("고객님의 청구서에는 로밍 요금이 포함되었습니다.", materials, null));
    }

    @Test
    void 말한_달의_청구서가_기록에_없으면_청구_내역_단정은_거절한다() {
        assertRejected(() -> guard.filterAnswer("2026년 8월 청구서를 보면 고객님의 청구서에는 로밍 요금이 포함되었습니다.",
                materials(), null));
    }

    @Test
    void 가입_정보가_없으면_청구_내역_단정은_그대로_거절한다() {
        var guest = AnswerMaterials.builder("이번 달 요금 얼마야?")
                .addSection(new ContextSection(UserIntentHandler.SECTION_TITLE, UserIntentHandler.LOGIN_REQUIRED_CONTENT))
                .build();

        assertRejected(() -> guard.filterAnswer("고객님의 청구서에는 부가서비스 요금 3,000원이 포함되었습니다.", guest, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "- 데이터: 150.0GB (소진 후 최대 5Mbps)",
            "- 데이터 제한: 150.0GB (소진 후 최대 5000Kbps)",
            "요금제 데이터는 150GB이며 소진 후 최대 0.005Gbps로 이용합니다.",
            "데이터 제공량은 150GB입니다. 소진 후 최대 5 mbps로 이용합니다.",
            "데이터 제공량은 150 GB입니다. 소진 후 최대 5000 kbps로 이용합니다.",
            "데이터 제공량은 150 gb입니다. 소진 후 최대 0.005 gbps로 이용합니다."
    })
    void 조회된_데이터량과_소진후_속도는_다른_단위로_검사한다(String answer) {
        assertThat(guard.filterAnswer(answer, materials(), null)).isEqualTo(answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "- 데이터: 151.0GB (소진 후 최대 5Mbps)",
            "- 데이터 제한: 151.0GB (소진 후 최대 5000Kbps)",
            "요금제 데이터는 151GB이며 소진 후 최대 0.005Gbps로 이용합니다."
    })
    void 소진후_속도가_맞아도_조회와_다른_데이터량은_전체_거절한다(String answer) {
        assertRejected(() -> guard.filterAnswer(answer, materials(), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "- 데이터: 150.0GB (소진 후 최대 6Mbps)",
            "- 데이터 제한: 150.0GB (소진 후 최대 6000Kbps)",
            "요금제 데이터는 150GB이며 소진 후 최대 0.006Gbps로 이용합니다."
    })
    void 데이터량이_맞아도_조회와_다른_소진후_속도는_전체_거절한다(String answer) {
        assertRejected(() -> guard.filterAnswer(answer, materials(), null));
    }

    @Test
    void 올바른_속도_표기라도_내부_메타데이터가_섞이면_전체_거절한다() {
        String answer = "데이터는 150GB이며 소진 후 최대 5Mbps로 이용합니다."
                + "\n\n**status**: ANSWER\n**evidence_ids**: []";
        assertRejected(() -> guard.filterAnswer(answer, materials(), null));
    }

    @Test
    void 조회된_사실_뒤의_조건부_문의_권유는_본문을_거절하지_않는다() {
        String body = "데이터는 150GB이며 소진 후 최대 5Mbps로 이용합니다.";
        var result = guard.inspectAnswer(body + " 추가 정보가 필요하시면 말씀해 주세요.", materials(), null);
        assertThat(result.rejected()).isFalse();
        assertThat(result.answer()).contains(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"말씀해 주세요.", "추가 정보가 필요하시면 말씀해 주세요.",
            "데이터는 150GB이며 소진 후 최대 5Mbps로 이용합니다. 회선 번호를 알려 주세요.",
            "데이터는 150GB이며 소진 후 최대 5Mbps로 이용합니다. 추가 문의가 필요하시면 비밀번호를 알려 주세요."})
    void 단독_되묻기와_실제_정보_제출_요구는_전체_거절한다(String answer) {
        assertRejected(() -> guard.filterAnswer(answer, materials(), null));
    }

    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LlmException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
    }

    /** AiService처럼 도구로 조회한 2026년 9월 청구서(총 61,950원, 부가서비스 11,200원)를 덧붙입니다. */
    private static AnswerMaterials withSeptemberBill(AnswerMaterials materials) {
        var recorder = new UserDataRecorder();
        recorder.recordBilling("010-****-5678", new BillingRecordResponseDto(2L, YearMonth.of(2026, 9), 75000, 11200,
                0, 0, 24250, 61950, LocalDate.of(2026, 9, 25), PaymentStatus.PAID, 61950, null, null));
        return materials.withToolRecords(new ContextSection(UserDataRecorder.SECTION_TITLE, recorder.describe()),
                recorder.cards());
    }

    private static AnswerMaterials lookupFailed() {
        return AnswerMaterials.builder("9월 청구서에 뭐 포함됐어?")
                .addSection(new ContextSection(UserIntentHandler.SECTION_TITLE, UserIntentHandler.LOOKUP_FAILED_CONTENT))
                .enableTool(AiTool.USER_DATA_LOOKUP)
                .userId(1L)
                .build();
    }

    private AnswerMaterials materials() {
        var month = YearMonth.of(2026, 10);
        var billing = UserDataFixtures.billing(month, PaymentStatus.UNPAID);
        var summary = new UserDataSummary(LocalDate.of(2026, 10, 12),
                new UserDataSummary.Profile("홍길동", 30, "서울", LocalDate.of(2024, 3, 2)),
                List.of(new UserDataSummary.SubscriptionSummary(
                        UserDataFixtures.subscription(1L, "010-1234-5678", SubscriptionStatus.ACTIVE),
                        UserDataFixtures.plan(), UserDataFixtures.usage(month), billing, List.of(billing),
                        UserDataFixtures.contract(), List.of(), List.of(), List.of())));
        return AnswerMaterials.builder("이번 달 요금 얼마야?")
                .addSection(new ContextSection(UserIntentHandler.SECTION_TITLE, summarizer.format(summary)))
                .enableTool(AiTool.USER_DATA_LOOKUP)
                .userId(1L)
                .userDataSummary(summary)
                .build();
    }

    private void assertRejected(String answer, AnswerMaterials materials, com.ubot.ai.dto.StoreMapResult stores) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.filterAnswer(answer, materials, stores))
                .isInstanceOfSatisfying(com.ubot.llm.exception.LlmException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.ubot.llm.exception.LlmErrorCode.LLM_OUTPUT_REJECTED));
    }
}
