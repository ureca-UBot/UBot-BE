package com.ubot.notification.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("방문 예약 리마인더 단계 테스트")
class ReservationReminderStageTest {

    @Test
    @DisplayName("남은 시간이 24시간 이내이고 6시간을 넘으면 24시간 전 단계이다")
    void dayBeforeStage() {
        assertThat(ReservationReminderStage.at(Duration.ofHours(24))).contains(ReservationReminderStage.D1);
        assertThat(ReservationReminderStage.at(Duration.ofHours(10))).contains(ReservationReminderStage.D1);
        assertThat(ReservationReminderStage.at(Duration.ofHours(6).plusMinutes(1)))
                .contains(ReservationReminderStage.D1);
    }

    @Test
    @DisplayName("남은 시간이 6시간 이내이고 1시간을 넘으면 6시간 전 단계이다")
    void sixHoursBeforeStage() {
        assertThat(ReservationReminderStage.at(Duration.ofHours(6))).contains(ReservationReminderStage.H6);
        assertThat(ReservationReminderStage.at(Duration.ofHours(5).plusMinutes(30)))
                .contains(ReservationReminderStage.H6);
        assertThat(ReservationReminderStage.at(Duration.ofHours(1).plusMinutes(1)))
                .contains(ReservationReminderStage.H6);
    }

    @Test
    @DisplayName("남은 시간이 1시간 이내이면 1시간 전 단계이다")
    void oneHourBeforeStage() {
        assertThat(ReservationReminderStage.at(Duration.ofHours(1))).contains(ReservationReminderStage.H1);
        assertThat(ReservationReminderStage.at(Duration.ofMinutes(1))).contains(ReservationReminderStage.H1);
    }

    @Test
    @DisplayName("24시간보다 많이 남았거나 이미 지났으면 단계가 없다")
    void noStageOutsideRange() {
        assertThat(ReservationReminderStage.at(Duration.ofHours(24).plusMinutes(1))).isEmpty();
        assertThat(ReservationReminderStage.at(Duration.ZERO)).isEmpty();
        assertThat(ReservationReminderStage.at(Duration.ofMinutes(-1))).isEmpty();
    }
}