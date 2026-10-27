package com.ubot.notification.enums;

import java.util.Optional;

/**
 * 데이터 사용량 알림을 보내는 단계 (기본 제공 데이터 대비 사용률)
 * 단계마다 사용률이 다르고, 알림 중복 방지 키에도 사용
 * 사용률이 여러 단계를 한 번에 넘어도 도달한 가장 높은 단계 하나만 선택
 */
public enum DataUsageStage {

    P80(80), // 80% 사용
    P100(100); // 100% 사용 (모두 소진)

    private final int percent;

    DataUsageStage(int percent) {
        this.percent = percent;
    }

    public int percent() {
        return percent;
    }

    // 알림을 보내기 시작하는 가장 낮은 사용률
    public static int lowestPercent() {
        return P80.percent;
    }

    /**
     * 사용한 데이터와 기본 제공 데이터(MB)로 도달한 가장 높은 단계를 반환
     * 기본 제공 데이터가 없거나(0 이하) 가장 낮은 단계에도 못 미치면 비어 있음
     */
    public static Optional<DataUsageStage> reached(long usedMb, long allowanceMb) {
        if (allowanceMb <= 0 || usedMb < 0) {
            return Optional.empty();
        }
        DataUsageStage[] stages = values();
        for (int i = stages.length - 1; i >= 0; i--) {
            if (usedMb * 100 >= allowanceMb * stages[i].percent) {
                return Optional.of(stages[i]);
            }
        }
        return Optional.empty();
    }
}