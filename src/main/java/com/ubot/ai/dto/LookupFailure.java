package com.ubot.ai.dto;

import java.util.List;

/**
 * 가입 정보·매장을 조회하지 못한 사유입니다. 사유가 코드에서 확정되므로 모델에게 안내를 쓰게 하지 않고,
 * 그 조회가 필요한 하위 질문이 ABSTAIN이면 일반 고지 대신 사유별 고지를 코드가 붙입니다.
 * args는 고지 문구의 %s 자리에 들어갈 값입니다(장소명, 회선 목록 등).
 */
public record LookupFailure(Kind kind, List<String> args) {

    public LookupFailure {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static LookupFailure of(Kind kind, String... args) {
        return new LookupFailure(kind, List.of(args));
    }

    /** 질문 전체를 이 사유로 답하지 못했을 때의 고지입니다. */
    public String wholeNotice() {
        return kind.whole.formatted(args.toArray());
    }

    /** 일부 하위 질문만 이 사유로 답하지 못했을 때, 원문 조각 뒤에 붙는 고지입니다. */
    public String partNotice() {
        return kind.part.formatted(args.toArray());
    }

    public enum Kind {
        LOGIN_REQUIRED(Target.USER_DATA,
                "가입 정보는 로그인한 사용자만 이용할 수 있습니다. 로그인한 뒤 다시 질문해 주세요.",
                "에 대해서는 로그인한 사용자만 이용할 수 있습니다. 로그인한 뒤 다시 질문해 주세요."),
        USER_NOT_FOUND(Target.USER_DATA,
                "가입 정보를 찾을 수 없어 확인해 드리기 어렵습니다.",
                "에 대해서는 가입 정보를 찾을 수 없어 확인해 드리기 어렵습니다."),
        USER_LOOKUP_FAILED(Target.USER_DATA,
                "가입 정보를 지금 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                "에 대해서는 가입 정보를 지금 조회하지 못했습니다. 잠시 후 다시 시도해 주세요."),
        /** args: 후보 회선 목록 */
        SUBSCRIPTION_UNSELECTED(Target.USER_DATA,
                "어느 회선인지 정할 수 없습니다. 다음 중 어느 번호인지 알려 주세요: %s",
                "에 대해서는 어느 회선인지 정할 수 없습니다. 다음 중 어느 번호인지 알려 주세요: %s"),
        /** args: 사용자가 말한 끝자리, 후보 회선 목록 */
        SUBSCRIPTION_NOT_MATCHED(Target.USER_DATA,
                "끝자리가 %s인 회선이 없습니다. 다음 중 어느 번호인지 알려 주세요: %s",
                "에 대해서는 끝자리가 %s인 회선이 없습니다. 다음 중 어느 번호인지 알려 주세요: %s"),
        STORE_NO_LOCATION(Target.STORE_DATA,
                "매장을 찾을 기준 위치가 없어 조회하지 못했습니다. 위치 권한을 허용하거나 지역·역 이름을 알려 주세요.",
                "에 대해서는 기준 위치가 없어 매장을 조회하지 못했습니다. 위치 권한을 허용하거나 지역·역 이름을 알려 주세요."),
        /** args: 찾지 못한 장소명 */
        STORE_PLACE_NOT_FOUND(Target.STORE_DATA,
                "'%s' 위치를 찾지 못했습니다. 정확한 지명이나 역 이름으로 다시 질문해 주세요.",
                "에 대해서는 '%s' 위치를 찾지 못했습니다. 정확한 지명이나 역 이름으로 다시 질문해 주세요."),
        STORE_LOOKUP_FAILED(Target.STORE_DATA,
                "매장 정보를 지금 조회하지 못했습니다. 잠시 후 다시 시도하거나 지역·역 이름으로 질문해 주세요.",
                "에 대해서는 매장 정보를 지금 조회하지 못했습니다. 잠시 후 다시 시도하거나 지역·역 이름으로 질문해 주세요.");

        private final Target target;
        private final String whole;
        private final String part;

        Kind(Target target, String whole, String part) {
            this.target = target;
            this.whole = whole;
            this.part = part;
        }

        public Target target() {
            return target;
        }
    }

    /** 이 사유로 답하지 못하는 하위 질문입니다. 하위 질문의 근거 FAQ intent로 가립니다. */
    public enum Target {
        USER_DATA, STORE_DATA
    }
}
