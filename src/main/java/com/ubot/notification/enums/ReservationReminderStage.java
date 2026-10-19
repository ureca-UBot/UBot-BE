package com.ubot.notification.enums;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * 방문 예약 리마인더를 보내는 단계
 * 단계마다 방문 몇 시간 전부터 알릴지가 다르고, 알림 중복 방지 키에도 사용
 * {@link #at(Duration)}이 남은 시간에 맞는 단계 중 가장 짧은 것을 고르기 때문에 단계는 짧은 순서로 선언
 */

public enum ReservationReminderStage {

    H1(Duration.ofHours(1)), // 1시간 전
    H6(Duration.ofHours(6)), // 6시간 전
    D1(Duration.ofHours(24)); // 하루 전

    private final Duration lead;

    ReservationReminderStage(Duration lead) {
        this.lead = lead;
    }

    // 방문 몇 시간 전부터 이 단계 알림을 보낼 수 있는지
    public Duration lead() {
        return lead;
    }

    /**
     * 방문까지 남은 시간에 해당하는 단계를 반환
     * 여러 단계에 걸치면 가장 짧은 단계 하나만 선택
     * 예: 남은 시간이 5시간 30분이면 24시간 전 단계가 아니라 6시간 전 단계
     * 이미 지났거나 24시간보다 많이 남았으면 비어 있음
     */
    public static Optional<ReservationReminderStage> at(Duration remaining) {
        if (remaining.isNegative() || remaining.isZero()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(stage -> remaining.compareTo(stage.lead) <= 0)
                .findFirst();
    }
}