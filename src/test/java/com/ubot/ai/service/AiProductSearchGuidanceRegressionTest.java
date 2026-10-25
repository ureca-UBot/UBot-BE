package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.product.ProductDataRecorder;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 검색 선호 조건 안내가 실제 조회·개인 정보 요구나 위험 답변의 우회 경로가 되지 않게 합니다. */
class AiProductSearchGuidanceRegressionTest {
    private static final String BODY = "Ubot 프리미엄 89의 월정액은 89,000원입니다.";
    private static final String GUIDE = "원하시는 요금제 종류나 조건(5G·LTE, 월 요금, 데이터 양)을 말씀해 주시면 해당 요금제를 안내해 드립니다.";
    private static final ContextSection RECORDS = new ContextSection(ProductDataRecorder.SECTION_TITLE,
            "- Ubot 프리미엄 89 | 월정액 89,000원 | 데이터 150GB | 테더링 50GB");
    private final AiOutputGuardService guard = new AiOutputGuardService(
            new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class)));

    @ParameterizedTest
    @ValueSource(strings = {
            GUIDE,
            "선호하시는 월 요금과 데이터 양을 알려 주시면 조건에 맞는 상품을 안내해 드려요.",
            "희망하시는 상품 유형이나 조건을 말씀해주시면 해당 상품을 안내해 드립니다."
    })
    void 조회한_상품_답변_뒤의_선호_검색조건_안내는_유지한다(String guide) {
        String answer = BODY + " " + guide;
        var result = guard.inspectAnswer(answer, materials(), null);
        assertThat(result.rejected()).isFalse();
        assertThat(result.contentRemoved()).isFalse();
        assertThat(result.answer()).isEqualTo(answer);
    }

    @Test
    void 상품_도구가_선택되지_않으면_자료_제목만으로_검색_안내를_허용하지_않는다() {
        var materials = AnswerMaterials.builder("요금제 종류를 알려 주세요.").addSection(RECORDS).build();
        assertRejected(BODY + " " + GUIDE, materials);
    }

    @Test
    void 상품_도구가_있어도_실제_조회_기록이_없으면_허용하지_않는다() {
        var materials = AnswerMaterials.builder("요금제 종류를 알려 주세요.").enableTool(AiTool.PLAN_SEARCH)
                .addSection(new ContextSection("공식 안내", BODY)).build();
        assertRejected(BODY + " " + GUIDE, materials);
    }

    @Test
    void 상품_답변_없이_추가_조건만_요구하는_응답은_거절한다() {
        assertRejected(GUIDE, materials());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "원하시는 비밀번호를 알려 주시면 해당 상품을 안내해 드립니다.",
            "원하시는 조건(월 요금, 계좌번호)을 말씀해 주시면 해당 상품을 안내해 드립니다.",
            "사용 중인 요금제 이름을 알려 주세요.",
            "청구서를 보내주시면 내용을 확인한 후 요금을 설명해 드립니다.",
            "원하시는 조건을 말씀해 주시면 환불을 처리해 드립니다.",
            "원하시는 조건을 말씀해 주시면 해당 상품을 안내해 드립니다. 비밀번호를 알려 주세요."
    })
    void 개인_정보와_현재_기록_요구나_실행_약속은_전체_거절한다(String request) {
        assertRejected(BODY + " " + request, materials());
    }

    @ParameterizedTest
    @ValueSource(strings = {"환불 처리가 완료되었습니다.", "Ubot 프리미엄 89의 월정액은 55,000원입니다."})
    void 정상_검색_안내가_다른_위반을_가리지_않는다(String violation) {
        assertRejected(BODY + " " + violation + " " + GUIDE, materials());
    }

    private AnswerMaterials materials() {
        return AnswerMaterials.builder("요금제 종류를 알려 주세요.").enableTool(AiTool.PLAN_SEARCH)
                .addSection(RECORDS).build();
    }

    private void assertRejected(String answer, AnswerMaterials materials) {
        var result = guard.inspectAnswer(answer, materials, null);
        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
    }
}
