package com.ubot.usage.repository;

/**
 * 데이터 사용량 알림 대상 조회 결과로, 알림 문구에 필요한 값만 담음
 * 사용량과 기본 제공 데이터는 MB 단위
 */
public interface DataUsageTarget {
    Long getUsageId();

    Long getUserId();

    Long getDataUsedMb();

    Long getAllowanceMb();
}