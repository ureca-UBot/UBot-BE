package com.ubot.ai.tool;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 도구 결과와 미리 조회한 자료를 LLM에 넘길 때 쓰는 공통 표기입니다. 사용자 정보와 상품 조회 결과가 같은 금액·속도 표기를 써야
 * 출력 검사가 답변의 수치를 근거와 같은 값으로 비교할 수 있습니다.
 */
public final class ToolFormats {

    public static final String UNKNOWN = "정보 없음";

    private ToolFormats() {
    }

    /** 천 단위 쉼표를 넣은 원 금액입니다. 값이 없으면 UNKNOWN입니다. */
    public static String won(Integer amount) {
        return amount == null ? UNKNOWN : String.format(Locale.ROOT, "%,d원", amount);
    }

    /** 1000Kbps 단위로 나누어떨어지면 Mbps로, 아니면 Kbps로 씁니다. */
    public static String speed(int kbps) {
        return kbps >= 1000 && kbps % 1000 == 0 ? kbps / 1000 + "Mbps" : kbps + "Kbps";
    }

    /** 비어 있는 문자열과 null을 UNKNOWN으로 바꿉니다. */
    public static String valueOrUnknown(Object value) {
        if (value instanceof String text) {
            return StringUtils.hasText(text) ? text : UNKNOWN;
        }
        return value == null ? UNKNOWN : value.toString();
    }
}
