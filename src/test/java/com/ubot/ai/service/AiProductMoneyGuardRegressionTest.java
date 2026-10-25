package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.OutputGuardResult.Reason;
import com.ubot.ai.tool.product.ProductDataRecorder;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 상품별 가격과 뒤에 붙은 제공량을 구분하며 실제 조회·모델·DB는 사용하지 않습니다. */
class AiProductMoneyGuardRegressionTest {
    private static final String BALANCE = "- Ubot 밸런스 59 | 5G | 대상 일반 | 월정액 59,000원"
            + " | 데이터 24GB(소진 후 최대 1Mbps) | 테더링 데이터 24GB";
    private static final String PREMIUM = "- Ubot 프리미엄 89 | 5G | 대상 일반 | 월정액 89,000원"
            + " | 데이터 100GB | 테더링 데이터 50GB";
    private final AiOutputGuardService guard = new AiOutputGuardService(
            new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)));

    @ParameterizedTest
    @ValueSource(strings = {
            "Ubot 밸런스 59의 월정액은 55,000원입니다.",
            "Ubot 밸런스 59의 월정액은 55,000원이며 테더링 데이터는 24GB입니다."
    })
    void 테더링_속성이_있어도_다른_상품_가격으로_해석하지_않고_오류는_전체_거절한다(String answer) {
        rejects(answer, materials("밸런스 59 월 요금은 얼마인가요?", BALANCE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ubot 밸런스 59의 월정액은 59,000원입니다.",
            "Ubot 밸런스 59의 월정액은 59,000원이며 테더링 데이터는 24GB입니다."
    })
    void 실제_상품의_가격과_테더링_제공량은_그대로_허용한다(String answer) {
        preserves(answer, materials("밸런스 59 월 요금은 얼마인가요?", BALANCE));
    }

    @Test
    void 여러_상품의_금액을_각자_대상과_비교한다() {
        var evidence = materials("밸런스 59와 프리미엄 89 월 요금을 알려 주세요.", BALANCE + "\n" + PREMIUM);
        preserves("Ubot 밸런스 59의 월정액은 59,000원입니다. Ubot 프리미엄 89의 월정액은 89,000원입니다.", evidence);
        rejects("Ubot 밸런스 59의 월정액은 89,000원입니다. Ubot 프리미엄 89의 월정액은 59,000원입니다.", evidence);
    }

    @Test
    void 상품_속성_열의_제공량이_달라도_같은_금액으로_섞지_않는다() {
        var evidence = materials("밸런스 59와 프리미엄 89 월 요금은 얼마인가요?", BALANCE + "\n" + PREMIUM);
        rejects("Ubot 밸런스 59의 월정액은 89,000원이며 테더링 데이터는 24GB입니다.", evidence);
        preserves("Ubot 프리미엄 89의 월정액은 89,000원이며 테더링 데이터는 50GB입니다.", evidence);
    }

    @Test
    void 기존_서비스_별칭의_금액_비교는_유지한다() {
        var evidence = materials("컬러링 월 요금은 얼마인가요?", "- Ubot 컬러링 | 월 990원 | 통화연결음 변경");
        preserves("Ubot 컬러링은 월 990원입니다.", evidence);
        rejects("Ubot 컬러링은 월 1,990원입니다.", evidence);
    }

    @Test
    void 대상_이름만_읽고_비교를_수행하지_못했으면_기존_수치_검사를_건너뛰지_않는다() {
        var evidence = materials("해당 요금제의 월 요금은 얼마인가요?", "월정액은 59,000원입니다.");
        preserves("밸런스 상품의 월정액은 59,000원입니다.", evidence);
        rejects("밸런스 상품의 월정액은 55,000원입니다.", evidence);
    }

    private AnswerMaterials materials(String question, String rows) {
        return AnswerMaterials.builder(question)
                .addSection(new ContextSection(ProductDataRecorder.SECTION_TITLE, rows)).build();
    }

    private void preserves(String answer, AnswerMaterials materials) {
        var result = guard.inspectAnswer(answer, materials, null);
        assertThat(result.rejected()).as(answer).isFalse();
        assertThat(result.contentRemoved()).as(answer).isFalse();
        assertThat(result.answer()).as(answer).isEqualTo(answer);
    }

    private void rejects(String answer, AnswerMaterials materials) {
        var result = guard.inspectAnswer(answer, materials, null);
        assertThat(result.rejected()).as(answer).isTrue();
        assertThat(result.answer()).as(answer).isNull();
        assertThat(result.reasons()).as(answer).contains(Reason.ANSWER_ERROR);
    }
}
