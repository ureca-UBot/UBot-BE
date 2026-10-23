package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.service.AiOutputGuardService;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 상품 가격·제공량·할인율은 FAQ가 아니라 상품 조회 도구 결과에만 있습니다. AiService가 그 결과를 검사 근거 섹션으로 덧붙였을 때
 * 출력 가드가 도구 결과로 답한 문장은 그대로 두고, 도구 결과에 없는 금액은 지우는지 확인합니다.
 */
class ProductOutputGuardTest {

    private static final String PLAN_ROW = "- Ubot 밸런스 59 | 5G | 대상 일반 | 월정액 59,000원 | 선택약정 25% 할인 시 월 44,250원"
            + " | 데이터 24GB(소진 후 최대 1Mbps) | 음성 기본 제공 | 문자 기본 제공 | 테더링 24GB";
    private static final String ADDON_ROW = "- Ubot 컬러링 | 월 990원 | 통화연결음 변경. 기본 제공 음원만 이용";

    private final AiOutputGuardService guard =
            new AiOutputGuardService(new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)));

    @ParameterizedTest
    @ValueSource(strings = {
            "Ubot 밸런스 59의 월정액은 59,000원입니다.",
            "Ubot 밸런스 59에 선택약정 할인을 받으면 월 44,250원입니다.",
            "선택약정은 월정액의 25%를 할인해 Ubot 밸런스 59는 월 44,250원입니다.",
            "요금제를 조회했습니다. Ubot 밸런스 59의 월정액은 59,000원입니다.",
            "Ubot 컬러링은 월 990원입니다."})
    void 도구가_조회한_상품_수치로_답한_문장은_그대로_둔다(String answer) {
        assertThat(guard.filterAnswer(answer, withProductRecords(), null)).isEqualTo(answer);
    }

    @Test
    void 도구_결과에_없는_상품_금액은_지운다() {
        String answer = "Ubot 밸런스 59의 월정액은 55,000원입니다. Ubot 컬러링은 월 990원입니다.";

        assertThat(guard.filterAnswer(answer, withProductRecords(), null))
                .doesNotContain("55,000원").contains("Ubot 컬러링은 월 990원입니다.");
    }

    @Test
    void 도구가_조회하지_않았으면_조회했다는_상품_문장을_사실로_보지_않는다() {
        String answer = "요금제를 조회했습니다. 요금제는 상품 조회 결과를 보고 고르시면 됩니다.";

        assertThat(guard.filterAnswer(answer, productMaterials().build(), null))
                .isEqualTo("요금제는 상품 조회 결과를 보고 고르시면 됩니다.");
    }

    @Test
    void 도구_결과와_다른_할인율은_지운다() {
        // 상품 의도에는 FAQ 답변이 근거로 들어가지 않아, 할인율의 근거는 도구 결과 행뿐입니다.
        String answer = "선택약정은 요금제 월정액의 30%를 할인합니다. Ubot 밸런스 59의 월정액은 59,000원입니다.";

        assertThat(guard.filterAnswer(answer, withProductRecords(), null)).isEqualTo("Ubot 밸런스 59의 월정액은 59,000원입니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "고객님의 요금제를 조회했습니다.",
            "사용 중인 요금제를 조회했습니다.",
            "제 요금제를 조회했습니다."})
    void 상품_조회_결과가_있어도_회원_본인의_상품을_조회했다는_문장은_사실로_보지_않는다(String claim) {
        // 카탈로그만 조회했고 회원 기록은 없는 상황입니다(비로그인 등).
        String answer = claim + " Ubot 밸런스 59의 월정액은 59,000원입니다.";

        assertThat(guard.filterAnswer(answer, withProductRecords(), null)).isEqualTo("Ubot 밸런스 59의 월정액은 59,000원입니다.");
    }

    private AnswerMaterials withProductRecords() {
        return productMaterials().build()
                .withSection(new ContextSection(ProductDataRecorder.SECTION_TITLE, PLAN_ROW + "\n" + ADDON_ROW));
    }

    /** ProductIntentHandler가 만드는 자료와 같습니다. FAQ 답변 없이 도구 안내 섹션만 있습니다. */
    private AnswerMaterials.Builder productMaterials() {
        return AnswerMaterials.builder("밸런스 59 약정 할인하면 얼마예요?")
                .addSection(new ContextSection("상품 정보",
                        "상품 가격·제공량·가입 조건·목록은 이 자료에 없습니다. 답하기 전에 상품 조회 도구를 호출해 확인하세요."));
    }
}
