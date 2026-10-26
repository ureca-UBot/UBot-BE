package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.bundle.enums.BundleType;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import org.junit.jupiter.api.Test;

class ConditionGroundingTest {

    @Test
    void 사용자가_말한_네트워크와_가입_대상만_근거로_본다() {
        assertThat(ConditionGrounding.mentionsNetwork("5G 요금제 알려줘", NetworkType.FIVE_G)).isTrue();
        assertThat(ConditionGrounding.mentionsNetwork("lte 요금제", NetworkType.LTE)).isTrue();
        assertThat(ConditionGrounding.mentionsNetwork("밸런스 요금제 알려줘", NetworkType.FIVE_G)).isFalse();
        assertThat(ConditionGrounding.mentionsTargetGroup("청년 요금제 종류", PlanTargetGroup.YOUTH)).isTrue();
        assertThat(ConditionGrounding.mentionsTargetGroup("어르신 요금제", PlanTargetGroup.SENIOR)).isTrue();
        assertThat(ConditionGrounding.mentionsTargetGroup("아이폰 요금제", PlanTargetGroup.KIDS)).isFalse();
        // 일반은 사용자가 따로 말하는 대상이 아니라 늘 뺍니다.
        assertThat(ConditionGrounding.mentionsTargetGroup("일반 요금제 알려줘", PlanTargetGroup.GENERAL)).isFalse();
    }

    @Test
    void 금액_데이터량_무제한은_그_표현이_있을_때만_근거로_본다() {
        assertThat(ConditionGrounding.mentionsMoney("5만원 이하 요금제")).isTrue();
        assertThat(ConditionGrounding.mentionsMoney("10만 이하")).isTrue();
        assertThat(ConditionGrounding.mentionsMoney("39,000원짜리")).isTrue();
        // 상품 이름의 숫자는 금액이 아닙니다.
        assertThat(ConditionGrounding.mentionsMoney("프리미엄 89 요금제")).isFalse();
        assertThat(ConditionGrounding.mentionsDataAmount("데이터 10GB 이상")).isTrue();
        assertThat(ConditionGrounding.mentionsDataAmount("100기가 요금제")).isTrue();
        assertThat(ConditionGrounding.mentionsDataAmount("데이터 100G 요금제")).isTrue();
        // "5G"는 네트워크라 데이터량이 아닙니다.
        assertThat(ConditionGrounding.mentionsDataAmount("5G 요금제")).isFalse();
        assertThat(ConditionGrounding.mentionsDataAmount("데이터 많은 요금제")).isFalse();
        assertThat(ConditionGrounding.mentionsUnlimited("데이터 무제한 요금제")).isTrue();
        assertThat(ConditionGrounding.mentionsUnlimited("밸런스 요금제 알려줘")).isFalse();
    }

    @Test
    void 결합_종류와_회선_수는_그_말이_있을_때만_근거로_본다() {
        assertThat(ConditionGrounding.mentionsBundleType("가족 결합 알려줘", BundleType.FAMILY)).isTrue();
        assertThat(ConditionGrounding.mentionsBundleType("3인 결합 상품 알려줘", BundleType.FAMILY)).isFalse();
        assertThat(ConditionGrounding.mentionsBundleType("인터넷이랑 같이 결합", BundleType.HOME)).isTrue();
        assertThat(ConditionGrounding.mentionsBundleType("친구랑 결합", BundleType.ETC)).isTrue();
        assertThat(ConditionGrounding.mentionsLineCount("3인 결합 상품 알려줘")).isTrue();
        assertThat(ConditionGrounding.mentionsLineCount("세 명이 결합하면")).isTrue();
        assertThat(ConditionGrounding.mentionsLineCount("가족 결합 알려줘")).isFalse();
    }

    @Test
    void 질문이_없으면_근거가_없는_것으로_본다() {
        assertThat(ConditionGrounding.mentionsMoney(null)).isFalse();
        assertThat(ConditionGrounding.mentionsNetwork("", NetworkType.LTE)).isFalse();
    }
}
