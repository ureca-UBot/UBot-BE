package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductDescriptionsTest {

    // 데이터 24GB, 테더링 24GB, 소진 후 1Mbps, 음성 100분, 문자 100건, 월정액 59,000원인 요금제의 컬럼 값입니다.
    private static final Set<String> PLAN_COLUMNS = ProductDescriptions.quantities(
            "MB", 24000L, "MB", 24000L, "Kbps", 1000, "분", 100, "건", 100, "원", 59000);

    @Test
    void 컬럼_값만_다시_쓴_구절은_빼고_규칙_구절은_남긴다() {
        String description = "월 데이터 24GB, 소진 후 최대 1Mbps로 계속 이용. 국내 일반 음성 100분, 국내 문자 100건. "
                + "표시 월정액은 부가세 포함, 기간 한정 할인 없이 유지. "
                + "가입 대상 만 19~34세. 만 35세가 되는 달 말일까지 이용 가능.";

        assertThat(ProductDescriptions.sentences(description, PLAN_COLUMNS, ProductDescriptions.PLAN_LABELS)).containsExactly(
                "표시 월정액은 부가세 포함, 기간 한정 할인 없이 유지.", "가입 대상 만 19~34세.", "만 35세가 되는 달 말일까지 이용 가능.");
    }

    @Test
    void 수량이_컬럼과_같아도_조건이_붙은_구절은_수량째_남긴다() {
        // 테더링 24GB는 컬럼에 있지만 "기본 제공량에서 함께 차감"은 컬럼으로 알 수 없는 규칙입니다.
        assertThat(ProductDescriptions.sentences("테더링 월 24GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감.",
                PLAN_COLUMNS, ProductDescriptions.PLAN_LABELS))
                .containsExactly("테더링 월 24GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감.");
        assertThat(ProductDescriptions.sentences("테더링 월 24GB.", PLAN_COLUMNS, ProductDescriptions.PLAN_LABELS)).isEmpty();

        Set<String> youthBundle = ProductDescriptions.quantities("원", 8000);
        assertThat(ProductDescriptions.sentences("만 19~34세 청년 요금제 2회선 이용 시 결합 전체 월 8,000원 할인. 결합 전체 월 8,000원 할인.",
                youthBundle, ProductDescriptions.BUNDLE_LABELS))
                .containsExactly("만 19~34세 청년 요금제 2회선 이용 시 결합 전체 월 8,000원 할인.");

        Set<String> roaming = ProductDescriptions.quantities("원", 11000, "MB", 1000L);
        assertThat(ProductDescriptions.sentences("이용 시작 시각부터 24시간마다 1GB 제공, 소진 후 해당 24시간 구간 종료까지 데이터 차단. "
                + "이용한 24시간 구간당 11,000원, 부가세 포함.", roaming, ProductDescriptions.ROAMING_LABELS))
                .containsExactly("이용 시작 시각부터 24시간마다 1GB 제공, 소진 후 해당 24시간 구간 종료까지 데이터 차단.",
                        "이용한 24시간 구간당 11,000원, 부가세 포함.");
    }

    @Test
    void 단위를_맞춰_컬럼_값과_비교한다() {
        Set<String> watch = ProductDescriptions.quantities("MB", 500L, "원", 11000);

        assertThat(ProductDescriptions.sentences("월 데이터 0.5GB, 소진 후 데이터 차단.", watch, ProductDescriptions.PLAN_LABELS))
                .containsExactly("소진 후 데이터 차단.");
        // 컬럼과 다른 값은 관리자가 한쪽만 고친 흔적일 수 있어 남깁니다.
        assertThat(ProductDescriptions.sentences("월 데이터 1GB.", watch, ProductDescriptions.PLAN_LABELS))
                .containsExactly("월 데이터 1GB.");
    }

    @Test
    void 컬럼에_없는_수량이_든_구절은_규칙이라_남긴다() {
        Set<String> bundle = ProductDescriptions.quantities("원", 6000);
        Set<String> dataAddon = ProductDescriptions.quantities("원", 3300);

        assertThat(ProductDescriptions.sentences("친구 휴대폰 2회선. 각 회선 월정액 19,000원 이상 이용 시 결합 전체 월 6,000원 할인.",
                bundle, ProductDescriptions.BUNDLE_LABELS))
                .containsExactly("친구 휴대폰 2회선.", "각 회선 월정액 19,000원 이상 이용 시 결합 전체 월 6,000원 할인.");
        assertThat(ProductDescriptions.sentences("매월 1GB 추가, 월 3,300원, 부가세 포함.", dataAddon, ProductDescriptions.ADDON_LABELS))
                .containsExactly("매월 1GB 추가, 부가세 포함.");
    }

    @Test
    void 설명이_비었거나_컬럼_재서술만_있으면_문장이_없다() {
        Set<String> addon = ProductDescriptions.quantities("원", 3300);

        assertThat(ProductDescriptions.sentences(null, addon, ProductDescriptions.ADDON_LABELS)).isEmpty();
        assertThat(ProductDescriptions.sentences(" ", addon, ProductDescriptions.ADDON_LABELS)).isEmpty();
        assertThat(ProductDescriptions.sentences("월 3,300원.", addon, ProductDescriptions.ADDON_LABELS)).isEmpty();
    }

    @Test
    void 수량_없는_재서술은_그_요금제_컬럼과_같을_때만_뺀다() {
        Set<String> unlimited = new HashSet<>(ProductDescriptions.PLAN_LABELS);
        unlimited.add(ProductDescriptions.DATA_UNLIMITED_LABEL);
        String description = "월 고속 데이터 무제한. 데이터 전용.";

        assertThat(ProductDescriptions.sentences(description, Set.of(), unlimited)).containsExactly("데이터 전용.");
        // 컬럼이 무제한이 아니면 라벨에 없어서, 설명과 컬럼이 어긋난 흔적으로 남깁니다.
        assertThat(ProductDescriptions.sentences(description, Set.of(), ProductDescriptions.PLAN_LABELS))
                .containsExactly("월 고속 데이터 무제한.", "데이터 전용.");
    }

    @Test
    void 설명의_구절_언급은_띄어쓰기를_무시한다() {
        assertThat(ProductDescriptions.mentions("테더링 한도 소진 후  테더링 차단.", ProductDescriptions.TETHERING_BLOCKED_RULE)).isTrue();
        assertThat(ProductDescriptions.mentions("테더링 한도 소진 후 최대 1Mbps.", ProductDescriptions.TETHERING_BLOCKED_RULE)).isFalse();
        assertThat(ProductDescriptions.mentions(null, ProductDescriptions.TETHERING_BLOCKED_RULE)).isFalse();
    }

    @Test
    void 카드에는_공통_문장만_빼고_고유_정보를_모두_마침표_없이_보여_준다() {
        List<String> sentences = List.of("표시 월정액은 부가세 포함.", "테더링 월 4GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감.",
                "가입 대상 만 0~12세.", "법정대리인 동의 필요.", "가상 Ubot 키즈가드 포함.", "위치 공유는 보호자 연결·동의 후 이용.");
        // 공통 문장은 수량을 가린 틀이라, 테더링 양이 다른 요금제에서 센 틀로도 걸러집니다.
        Set<String> common = Set.of("표시 월정액은 부가세 포함.",
                ProductDescriptions.shape("테더링 월 24GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감."));

        assertThat(ProductDescriptions.highlights(sentences, common))
                .containsExactly("가입 대상 만 0~12세", "법정대리인 동의 필요", "가상 Ubot 키즈가드 포함", "위치 공유는 보호자 연결·동의 후 이용");
    }

    @Test
    void 약정_없음_구절이_있는_요금제만_무약정이다() {
        assertThat(ProductDescriptions.isNoContract("월 데이터 120GB. 온라인 셀프개통 전용, 약정 없음.")).isTrue();
        assertThat(ProductDescriptions.isNoContract("USIM·eSIM 가입 가능. 약정 없음.")).isTrue();
        assertThat(ProductDescriptions.isNoContract("온라인·매장 가입 가능. 별도 결합 가입 시 해당 결합 할인만 적용.")).isFalse();
        assertThat(ProductDescriptions.isNoContract("선택약정 없음 시 위약금 미발생.")).isFalse();
        assertThat(ProductDescriptions.isNoContract(null)).isFalse();
    }
}
