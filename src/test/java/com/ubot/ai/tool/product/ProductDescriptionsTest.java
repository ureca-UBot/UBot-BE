package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductDescriptionsTest {

    @Test
    void 컬럼에_있는_수량_구절은_빼고_규칙_구절은_남긴다() {
        String description = "월 데이터 24GB, 소진 후 최대 1Mbps로 계속 이용. 국내 일반 음성 100분, 국내 문자 100건. "
                + "테더링 월 24GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감. 표시 월정액은 부가세 포함, 기간 한정 할인 없이 유지. "
                + "가입 대상 만 19~34세. 만 35세가 되는 달 말일까지 이용 가능.";

        assertThat(ProductDescriptions.sentences(description)).containsExactly(
                "표시 월정액은 부가세 포함, 기간 한정 할인 없이 유지.", "가입 대상 만 19~34세.", "만 35세가 되는 달 말일까지 이용 가능.");
    }

    @Test
    void 한_문장에서_수량_구절만_빼고_나머지_구절은_남긴다() {
        assertThat(ProductDescriptions.sentences("월 데이터 0.5GB, 소진 후 데이터 차단.")).containsExactly("소진 후 데이터 차단.");
        assertThat(ProductDescriptions.sentences("이용한 24시간 구간당 11,000원, 부가세 포함.")).containsExactly("부가세 포함.");
        assertThat(ProductDescriptions.sentences("카페 월 5,000포인트. 대표 휴대폰 1회선에서만 차감"))
                .containsExactly("카페 월 5,000포인트.", "대표 휴대폰 1회선에서만 차감.");
    }

    @Test
    void 설명이_비었으면_문장이_없다() {
        assertThat(ProductDescriptions.sentences(null)).isEmpty();
        assertThat(ProductDescriptions.sentences(" ")).isEmpty();
        assertThat(ProductDescriptions.sentences("월 3,300원.")).isEmpty();
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
