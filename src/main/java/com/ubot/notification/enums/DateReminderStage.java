package com.ubot.notification.enums;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * 날짜(로밍 시작·종료, 계약·할부 만료)를 기준으로 알림을 보내는 단계
 * 단계마다 기준 날짜 며칠 전부터 알릴지가 다르고, 알림 중복 방지 키에도 사용
 * 알림 종류마다 쓰는 단계가 달라서 {@link #at(long, DateReminderStage...)}에 쓸 단계를 넘김
 * (예: 로밍은 D0·D1, 계약·할부 만료는 D1·D7·D30)
 */
public enum DateReminderStage {

    D0(0), // 당일
    D1(1), // 하루 전
    D7(7), // 7일 전
    D30(30); // 30일 전

    private final int days;

    DateReminderStage(int days) {
        this.days = days;
    }

    // 기준 날짜 며칠 전부터 이 단계 알림을 보낼 수 있는지
    public int days() {
        return days;
    }

    // 이 단계가 시작되는 시점 (기준 날짜 - 단계 일수의 0시)
    public LocalDateTime startsAt(LocalDate targetDate) {
        return targetDate.minusDays(days).atStartOfDay();
    }

    /**
     * 기준 날짜까지 남은 일수에 해당하는 단계를 넘겨받은 단계 중에서 반환
     * 여러 단계에 걸치면 가장 짧은 단계 하나만 선택
     * 예: 계약 만료가 5일 남았으면 30일 전 단계가 아니라 7일 전 단계
     * 이미 지났거나 가장 긴 단계보다 많이 남았으면 비어 있음
     * 가장 짧은 단계가 D1이면 당일(0일 남음)도 D1 단계로 취급
     */
    public static Optional<DateReminderStage> at(long daysLeft, DateReminderStage... stages) {
        if (daysLeft < 0) {
            return Optional.empty();
        }
        return Arrays.stream(stages)
                .sorted(Comparator.comparingInt(DateReminderStage::days))
                .filter(stage -> daysLeft <= stage.days)
                .findFirst();
    }
}