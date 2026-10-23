package com.ubot.ai.dto;

import java.util.Set;

/**
 * 출력 검사의 내부 결과입니다. 사유는 후보 등록 결정이 아니며 외부 응답이나 DB에 직접 저장하지 않습니다.
 * contentRemoved는 문장·절·블록 단위로 지운 내용이 있었는지입니다. 요청 문구·FAQ 번호처럼 표현만 정리한 경우는 포함하지 않습니다.
 */
public record OutputGuardResult(String answer, boolean rejected, Set<Reason> reasons, boolean contentRemoved) {

    public OutputGuardResult {
        // 차단된 원문이 후속 처리에서 답변으로 사용되지 않도록 보관하지 않습니다.
        answer = rejected ? null : answer;
        reasons = Set.copyOf(reasons);
    }

    public OutputGuardResult(String answer, boolean rejected, Set<Reason> reasons) {
        this(answer, rejected, reasons, false);
    }

    public enum Reason {
        POLICY_VIOLATION,
        FORMAT_ERROR,
        FALSE_EXECUTION,
        ANSWER_ERROR,
        FAQ_SHORTAGE_SUSPECTED,
        USER_INFORMATION_OR_LOOKUP_MISSING,
        UNCLASSIFIED
    }
}
