package com.ubot.notification.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("날짜 기준 리마인더 단계 테스트")
class DateReminderStageTest {

    @Test
    @DisplayName("로밍은 당일이면 D0, 하루 전이면 D1이고 그보다 많이 남았으면 단계가 없다")
    void roamingStages() {
        assertThat(DateReminderStage.at(0, DateReminderStage.D0, DateReminderStage.D1))
                .contains(DateReminderStage.D0);
        assertThat(DateReminderStage.at(1, DateReminderStage.D0, DateReminderStage.D1))
                .contains(DateReminderStage.D1);
        assertThat(DateReminderStage.at(2, DateReminderStage.D0, DateReminderStage.D1)).isEmpty();
    }

    @Test
    @DisplayName("만료 알림은 남은 일수에 맞는 가장 짧은 단계 하나를 고르고 당일은 D1이다")
    void expiryStages() {
        DateReminderStage[] stages = { DateReminderStage.D1, DateReminderStage.D7, DateReminderStage.D30 };

        assertThat(DateReminderStage.at(0, stages)).contains(DateReminderStage.D1);
        assertThat(DateReminderStage.at(1, stages)).contains(DateReminderStage.D1);
        assertThat(DateReminderStage.at(2, stages)).contains(DateReminderStage.D7);
        assertThat(DateReminderStage.at(7, stages)).contains(DateReminderStage.D7);
        assertThat(DateReminderStage.at(8, stages)).contains(DateReminderStage.D30);
        assertThat(DateReminderStage.at(30, stages)).contains(DateReminderStage.D30);
    }

    @Test
    @DisplayName("단계를 어떤 순서로 넘겨도 가장 짧은 단계를 고른다")
    void ignoresArgumentOrder() {
        assertThat(DateReminderStage.at(5, DateReminderStage.D30, DateReminderStage.D1, DateReminderStage.D7))
                .contains(DateReminderStage.D7);
    }

    @Test
    @DisplayName("이미 지났거나 가장 긴 단계보다 많이 남았으면 단계가 없다")
    void noStageOutsideRange() {
        DateReminderStage[] stages = { DateReminderStage.D1, DateReminderStage.D7, DateReminderStage.D30 };

        assertThat(DateReminderStage.at(31, stages)).isEmpty();
        assertThat(DateReminderStage.at(-1, stages)).isEmpty();
    }

    @Test
    @DisplayName("단계 시작 시점은 기준 날짜에서 단계 일수를 뺀 날의 0시이다")
    void startsAt() {
        LocalDate target = LocalDate.of(2026, 10, 23);

        assertThat(DateReminderStage.D7.startsAt(target)).isEqualTo(LocalDateTime.of(2026, 10, 16, 0, 0));
        assertThat(DateReminderStage.D0.startsAt(target)).isEqualTo(LocalDateTime.of(2026, 10, 23, 0, 0));
    }
}