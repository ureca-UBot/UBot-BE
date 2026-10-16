package com.ubot.notification.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터 사용량 리마인더 단계 테스트")
class DataUsageStageTest {

    @Test
    @DisplayName("80% 미만이면 단계가 없다")
    void belowLowestStage() {
        assertThat(DataUsageStage.reached(7_999, 10_000)).isEmpty();
        assertThat(DataUsageStage.reached(0, 10_000)).isEmpty();
    }

    @Test
    @DisplayName("80% 이상 100% 미만이면 80% 단계이다")
    void eightyPercentStage() {
        assertThat(DataUsageStage.reached(8_000, 10_000)).contains(DataUsageStage.P80);
        assertThat(DataUsageStage.reached(9_999, 10_000)).contains(DataUsageStage.P80);
    }

    @Test
    @DisplayName("100% 이상이면 80%를 건너뛰고 100% 단계이다")
    void hundredPercentStage() {
        assertThat(DataUsageStage.reached(10_000, 10_000)).contains(DataUsageStage.P100);
        assertThat(DataUsageStage.reached(12_000, 10_000)).contains(DataUsageStage.P100);
    }

    @Test
    @DisplayName("기본 제공 데이터가 없거나 사용량이 음수이면 단계가 없다")
    void invalidInput() {
        assertThat(DataUsageStage.reached(100, 0)).isEmpty();
        assertThat(DataUsageStage.reached(-1, 10_000)).isEmpty();
    }

    @Test
    @DisplayName("알림을 시작하는 가장 낮은 사용률은 80%이다")
    void lowestPercent() {
        assertThat(DataUsageStage.lowestPercent()).isEqualTo(80);
    }
}