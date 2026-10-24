package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import java.util.Comparator;
import java.util.List;

/**
 * 조건 없이 "요금제 종류"를 물었을 때의 답변입니다. 요금제는 100개가 넘어 월정액 낮은 순 5개만 보여 주면 종류를 알 수 없으므로,
 * 상품 이름 사전의 시리즈(ProductNameIndex.planSeries)별로 개수와 월정액 범위를 보여 주고 원하는 종류나 조건을 말해 달라고 안내합니다.
 * 특정 요금제를 고른 답변이 아니라 카드는 붙이지 않습니다.
 *
 * @param groups 시리즈별 묶음. 시리즈에 들지 않는 요금제는 이름 그대로 하나씩 들어 있습니다.
 */
public record PlanSeriesOverview(List<Group> groups) implements ProductListAnswer {

    // 시스템 프롬프트가 되묻지 않게 하므로 물음표 질문 대신 안내형 문장으로 끝냅니다.
    static final String GUIDE = "원하시는 요금제 종류나 조건(5G·LTE, 월 요금, 데이터 양)을 말씀해 주시면 해당 요금제를 안내해 드립니다.";

    /**
     * @param name   시리즈 이름("프리미엄"). 시리즈에 들지 않는 요금제면 요금제 이름 그대로("Ubot 통화 전용")입니다.
     * @param series 시리즈 묶음인지입니다.
     */
    public record Group(String name, boolean series, int count, Integer minFee, Integer maxFee) {
    }

    public PlanSeriesOverview {
        // 시리즈를 월정액이 낮은 순으로 먼저 쓰고, 시리즈에 들지 않는 요금제는 뒤에 둡니다.
        groups = groups.stream()
                .sorted(Comparator.comparing((Group group) -> !group.series())
                        .thenComparing(Group::minFee, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Group::name))
                .toList();
    }

    public int total() {
        return groups.stream().mapToInt(Group::count).sum();
    }

    @Override
    public String answer() {
        StringBuilder text = new StringBuilder("Ubot 요금제는 ").append(total()).append("가지이며, 다음 종류가 있습니다.");
        for (Group group : groups) {
            text.append("\n- ").append(group.name()).append(" (");
            if (group.series()) {
                text.append(group.count()).append("가지");
                if (group.minFee() != null) {
                    text.append(", ");
                }
            }
            if (group.minFee() != null) {
                text.append("월 ").append(ToolFormats.won(group.minFee()));
                if (!group.minFee().equals(group.maxFee())) {
                    text.append("~").append(ToolFormats.won(group.maxFee()));
                }
            }
            text.append(')');
        }
        return text.append("\n\n").append(GUIDE).toString();
    }

    @Override
    public List<ProductCard> products() {
        return List.of();
    }
}
