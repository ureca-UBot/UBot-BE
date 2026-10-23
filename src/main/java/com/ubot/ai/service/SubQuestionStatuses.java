package com.ubot.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

/**
 * 칸 형식(faq-system-slot.txt)에서 하위 질문별 status로 전체 status를 정하고,
 * 답하지 못한 하위 질문의 고지 문장을 코드로 만듭니다. 모델은 고지 문장을 쓰지 않으며, 쓰더라도 그 칸은 버립니다.
 */
final class SubQuestionStatuses {
    static final String ANSWER = "ANSWER";
    static final String ABSTAIN = "ABSTAIN";
    private static final String PARTIAL = "PARTIAL";
    /** ANSWER가 없고 서로 다른 값이 섞였을 때 고르는 순서입니다. */
    private static final List<String> PRIORITY = List.of("CONFLICT", ABSTAIN, "OUT_OF_SCOPE");

    /** 질문 전체를 답하지 못했을 때의 고지입니다. */
    private static final Map<String, String> WHOLE_NOTICES = Map.of(
            ABSTAIN, "문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.",
            "OUT_OF_SCOPE", "문의하신 내용은 통신 서비스 상담 범위 밖이라 안내해 드리기 어렵습니다.",
            "CONFLICT", "문의하신 내용은 안내 자료끼리 내용이 서로 달라 확정해 드리기 어렵습니다.");
    /** 일부 하위 질문만 답하지 못했을 때, 원문 조각 뒤에 붙는 고지입니다. */
    private static final Map<String, String> PART_NOTICES = Map.of(
            ABSTAIN, "에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다.",
            "OUT_OF_SCOPE", "에 대해서는 통신 서비스 상담 범위 밖이라 안내해 드리기 어렵습니다.",
            "CONFLICT", "에 대해서는 안내 자료끼리 내용이 서로 달라 확정해 드리기 어렵습니다.");

    private SubQuestionStatuses() {
    }

    /**
     * 모두 ANSWER면 ANSWER, ANSWER와 나머지가 섞이면 PARTIAL, ANSWER 없이 모두 같으면 그 값,
     * ANSWER 없이 서로 다르면 CONFLICT > ABSTAIN > OUT_OF_SCOPE 순서로 고릅니다. 비었거나 null이 있으면 null입니다.
     */
    static String combine(List<String> statuses) {
        if (statuses == null || statuses.isEmpty() || statuses.stream().anyMatch(Objects::isNull)) {
            return null;
        }
        if (statuses.stream().allMatch(ANSWER::equals)) {
            return ANSWER;
        }
        if (statuses.contains(ANSWER)) {
            return PARTIAL;
        }
        return PRIORITY.stream().filter(statuses::contains).findFirst().orElse(null);
    }

    /**
     * ANSWER가 아닌 하위 질문의 고지입니다. 하위 질문이 하나이거나 모든 하위 질문이 같은 status로 답하지 못했으면
     * 질문 전체에 대한 문장 하나를, 아니면 status별로 원문 조각을 묶어 한 문장씩 만듭니다. 고지할 것이 없으면 빈 문자열입니다.
     * labels[i]는 i번째 하위 질문의 원문 조각이며, 없으면 "N번째 질문"으로 씁니다.
     */
    static String notice(List<String> statuses, List<String> labels) {
        if (statuses.stream().allMatch(ANSWER::equals)) {
            return "";
        }
        if (!needsPartNotice(statuses)) {
            return WHOLE_NOTICES.getOrDefault(statuses.getFirst(), "");
        }
        Map<String, List<String>> partsByStatus = new LinkedHashMap<>();
        for (int index = 0; index < statuses.size(); index++) {
            String status = statuses.get(index);
            if (PART_NOTICES.containsKey(status)) {
                partsByStatus.computeIfAbsent(status, ignored -> new ArrayList<>()).add(label(labels, index));
            }
        }
        return partsByStatus.entrySet().stream()
                .map(entry -> quote(entry.getValue()) + PART_NOTICES.get(entry.getKey()))
                .collect(Collectors.joining(" "));
    }

    /** 원문 조각을 넣은 하위 고지를 낼지입니다. 하위 질문이 둘 이상이고, 답하지 못한 것이 있으며, 모두 같은 status는 아닐 때입니다. */
    static boolean needsPartNotice(List<String> statuses) {
        return statuses.size() > 1 && !statuses.stream().allMatch(ANSWER::equals)
                && statuses.stream().distinct().count() > 1;
    }

    /** 모델 답변 뒤에 고지를 붙입니다. 둘 중 하나가 비면 다른 하나만 씁니다. */
    static String compose(String answer, String notice) {
        if (!StringUtils.hasText(notice)) {
            return answer;
        }
        return StringUtils.hasText(answer) ? answer.strip() + "\n\n" + notice : notice;
    }

    private static String quote(List<String> texts) {
        return texts.stream().map(text -> "「" + text + "」").collect(Collectors.joining(", "));
    }

    private static String label(List<String> labels, int index) {
        String text = index < labels.size() ? labels.get(index) : null;
        if (!StringUtils.hasText(text)) {
            return (index + 1) + "번째 질문";
        }
        return text.strip().replaceAll("[\\s.?!？。,]+$", "");
    }
}
