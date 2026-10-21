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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 가입 정보 요약으로 답한 정상 답변을 출력 가드가 지우지 않는지 확인합니다. */
class UserDataOutputGuardTest {

    private final AiOutputGuardService guard =
            new AiOutputGuardService(new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)));
    private final UserDataSummarizer summarizer = new UserDataSummarizer(mock(UserService.class),
            mock(SubscriptionService.class), mock(UsageService.class), mock(BillingService.class),
            mock(ContractService.class), UserDataFixtures.CLOCK);

    static Stream<String> summaryAnswers() {
        return Stream.of(
                "2026년 10월 청구서(9월 사용분) 기준으로 안내드립니다. 이번 달 요금은 총 78,000원입니다.",
                "이번 달 청구 금액은 78,000원이고 납부 기한은 2026-10-25입니다.",
                "고객님의 요금제는 5G 스탠다드이며 월정액은 75,000원입니다.",
                "이번 달 데이터는 " + UserDataSummarizer.megabytes(12595) + " 사용하셨고, "
                        + UserDataSummarizer.megabytes(141005) + " 남았습니다.",
                "고객님의 청구서에는 부가서비스 요금 3,000원이 포함되었습니다.",
                "고객님의 청구 내역을 확인한 결과, 10월 청구서 미납액은 78,000원입니다.");
    }

    @ParameterizedTest
    @MethodSource("summaryAnswers")
    void 가입_정보_요약으로_답한_정상_답변은_그대로_둔다(String answer) {
        assertThat(guard.filterAnswer(answer, materials(), null)).isEqualTo(answer);
    }

    @Test
    void 도구로_조회한_지난_달_청구서_금액도_근거로_본다() {
        var recorder = new UserDataRecorder();
        recorder.recordBilling("010-****-5678", new BillingRecordResponseDto(2L, YearMonth.of(2026, 9), 75000, 11200,
                0, 0, 24250, 61950, LocalDate.of(2026, 9, 25), PaymentStatus.PAID, 61950, null, null));
        var materials = materials().withSection(new ContextSection(UserIntentHandler.SECTION_TITLE, recorder.describe()));
        String answer = "2026년 9월 청구서(8월 사용분) 기준으로 안내드립니다. 9월 요금은 총 61,950원입니다.";

        assertThat(guard.filterAnswer(answer, materials, null)).isEqualTo(answer);
    }

    @Test
    void 가입_정보와_다른_청구_금액은_지운다() {
        String answer = "2026년 10월 청구서(9월 사용분) 기준으로 안내드립니다. 이번 달 요금은 총 99,000원입니다.";

        assertThat(guard.filterAnswer(answer, materials(), null))
                .isEqualTo("2026년 10월 청구서(9월 사용분) 기준으로 안내드립니다.");
    }

    @Test
    void 가입_정보가_없으면_청구_내역_단정은_그대로_거절한다() {
        var guest = AnswerMaterials.builder("이번 달 요금 얼마야?")
                .addSection(new ContextSection(UserIntentHandler.SECTION_TITLE, UserIntentHandler.LOGIN_REQUIRED_CONTENT))
                .build();

        assertThatThrownBy(() -> guard.filterAnswer("고객님의 청구서에는 부가서비스 요금 3,000원이 포함되었습니다.",
                guest, null)).isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
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
}
