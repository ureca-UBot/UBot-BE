package com.ubot.ai.tool.product;

/** 요금제 조회 도구의 정렬 기준입니다. */
public enum PlanSort {
    /** 월정액이 낮은 순(기본) */
    MONTHLY_FEE_ASC,
    /** 데이터가 많은 순. 무제한이 먼저 오고, 같으면 월정액이 낮은 순 */
    DATA_DESC
}
