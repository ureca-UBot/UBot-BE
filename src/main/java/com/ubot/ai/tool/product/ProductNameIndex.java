package com.ubot.ai.tool.product;

import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.bundle.repository.BundleProductRepository;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.plan.repository.PlanRepository;
import com.ubot.roaming.repository.RoamingProductRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 질문에 판매 중인 상품 이름이나 로밍 국가가 있는지 보고 조회할 상품 종류를 찾습니다.
 * "V컬러링 얼마야?"처럼 종류를 가리키는 말 없이 상품 이름만 말한 질문을 위해 씁니다.
 * 상품은 관리자 화면에서만 바뀌므로 이름 목록을 잠깐 메모리에 두고, 조회에 실패하면 빈 결과로 넘어갑니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductNameIndex {

    static final Duration TTL = Duration.ofMinutes(5);

    private final PlanRepository planRepository;
    private final AddonServiceRepository addonServiceRepository;
    private final BundleProductRepository bundleProductRepository;
    private final RoamingProductRepository roamingProductRepository;
    private final Clock clock;

    private volatile Snapshot snapshot;

    /** 질문에 이름이 나온 상품의 종류입니다. 이름이 긴 상품을 먼저 찾아 "북패스 7GB+"를 부가서비스 "북패스"로 세지 않습니다. */
    public Set<ProductType> typesMentionedIn(String question) {
        if (!StringUtils.hasText(question)) {
            return Set.of();
        }
        Snapshot current = current();
        Set<ProductType> types = EnumSet.noneOf(ProductType.class);
        ProductNames.findMentioned(question, current.names(), Named::name).forEach(named -> types.add(named.type()));
        return types;
    }

    /**
     * 질문에 "프리미엄 요금제"처럼 요금제 시리즈 이름이 하나만 있으면 그 이름입니다. 없거나 둘 이상이면 비어 있습니다.
     * 작은 모델은 "프리미엄 요금제 종류"의 "프리미엄"을 이름이 아니라 분류로 보고 이름 조건을 비우기도 해서,
     * 요금제 도구가 이 값으로 이름 조건을 채웁니다(planSeriesIn 참고).
     */
    public Optional<String> planSeriesMentionedIn(String question) {
        List<String> found = planSeriesIn(question, current().planSeries());
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /**
     * 판매 중인 요금제를 시리즈별로 묶은 개요입니다. 시리즈 판별(planSeries)과 같은 목록에서 만들어, 질문에서 알아보는 시리즈와
     * 개요에 보여 주는 시리즈가 같습니다. 요금제 목록을 불러오지 못했으면 비어 있습니다.
     */
    public Optional<PlanSeriesOverview> planOverview() {
        PlanSeriesOverview overview = current().planOverview();
        return overview.groups().isEmpty() ? Optional.empty() : Optional.of(overview);
    }

    /** 질문에 요금제 시리즈 이름이 하나라도 있는지입니다. 둘 이상이라 하나로 정하지 못한 경우도 참입니다. */
    public boolean mentionsPlanSeries(String question) {
        return !planSeriesIn(question, current().planSeries()).isEmpty();
    }

    /**
     * 질문에서 시리즈 이름 바로 뒤에 "요금제"가 오는 것만 찾습니다. "데이터 많은 요금제"의 "데이터"처럼
     * 시리즈 이름과 같은 일반 단어를 시리즈로 잘못 보지 않기 위해서입니다.
     */
    static List<String> planSeriesIn(String question, Collection<String> series) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        return series.stream()
                .filter(word -> Pattern.compile("(?<![가-힣A-Za-z0-9])" + Pattern.quote(word) + "\\s*요금제",
                        Pattern.CASE_INSENSITIVE).matcher(question).find())
                .toList();
    }

    /**
     * "Ubot 프리미엄 89"의 "프리미엄"처럼 요금제 이름에서 "Ubot" 다음 첫 단어입니다.
     * 두 개 이상의 요금제가 같이 쓰는 말만 시리즈로 봅니다. "Ubot 통화 전용"의 "통화"처럼 한 요금제만 쓰는 말은
     * 질문에 흔히 나오는 일반 단어라 뺍니다.
     */
    static List<String> planSeries(Collection<String> planNames) {
        Map<String, Long> counts = planNames.stream()
                .map(name -> name.strip().split("\\s+"))
                .filter(words -> words.length >= 2 && words[0].equalsIgnoreCase("Ubot"))
                .collect(Collectors.groupingBy(words -> words[1], Collectors.counting()));
        return counts.entrySet().stream().filter(entry -> entry.getValue() >= 2).map(Map.Entry::getKey).sorted().toList();
    }

    /** 시리즈마다 개수와 월정액 범위를 모으고, 시리즈에 들지 않는 요금제는 이름 그대로 하나씩 둡니다. */
    static PlanSeriesOverview overview(Collection<PlanFee> plans, Collection<String> series) {
        Map<String, List<PlanFee>> bySeries = plans.stream()
                .collect(Collectors.groupingBy(plan -> seriesOf(plan.name(), series).orElse(""), Collectors.toList()));
        List<PlanSeriesOverview.Group> groups = new ArrayList<>();
        bySeries.forEach((name, members) -> {
            if (name.isEmpty()) {
                members.forEach(plan -> groups.add(
                        new PlanSeriesOverview.Group(plan.name().strip(), false, 1, plan.fee(), plan.fee())));
                return;
            }
            IntSummaryStatistics fees = members.stream().map(PlanFee::fee).filter(Objects::nonNull)
                    .mapToInt(Integer::intValue).summaryStatistics();
            boolean priced = fees.getCount() > 0;
            groups.add(new PlanSeriesOverview.Group(name, true, members.size(),
                    priced ? fees.getMin() : null, priced ? fees.getMax() : null));
        });
        return new PlanSeriesOverview(groups);
    }

    private static Optional<String> seriesOf(String planName, Collection<String> series) {
        String[] words = planName.strip().split("\\s+");
        return words.length >= 2 && words[0].equalsIgnoreCase("Ubot") && series.contains(words[1])
                ? Optional.of(words[1]) : Optional.empty();
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        Instant now = clock.instant();
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(now)) {
            return cached;
        }
        try {
            List<PlanFee> plans = new ArrayList<>();
            List<Named> names = load(plans);
            List<String> series = planSeries(plans.stream().map(PlanFee::name).toList());
            Snapshot loaded = new Snapshot(names, series, overview(plans, series), now);
            snapshot = loaded;
            return loaded;
        } catch (RuntimeException exception) {
            // 이름 보강은 도구 선택을 돕는 단계라, 실패해도 FAQ 카테고리와 키워드로 고른 도구로 진행합니다.
            log.warn("상품 이름 목록을 불러오지 못했습니다", exception);
            return cached != null ? cached : new Snapshot(List.of(), List.of(), new PlanSeriesOverview(List.of()), now);
        }
    }

    /** 판매 중인 상품 이름을 모으고, 요금제는 시리즈 개요를 만들도록 월정액과 함께 plans에도 담습니다. */
    private List<Named> load(List<PlanFee> plans) {
        List<Named> names = new ArrayList<>();
        planRepository.findAll(ProductSpecification.filter(null, MasterStatus.ACTIVE, false)).forEach(plan -> {
            names.add(new Named(ProductType.PLAN, plan.getName()));
            if (StringUtils.hasText(plan.getName())) {
                plans.add(new PlanFee(plan.getName(), plan.getMonthlyFee()));
            }
        });
        addonServiceRepository.findAll(ProductSpecification.filter(null, MasterStatus.ACTIVE, false))
                .forEach(addon -> names.add(new Named(ProductType.ADDON, addon.getName())));
        bundleProductRepository.findAll(ProductSpecification.filter(null, MasterStatus.ACTIVE, false))
                .forEach(bundle -> names.add(new Named(ProductType.BUNDLE, bundle.getName())));
        roamingProductRepository.findAll(ProductSpecification.filter(null, MasterStatus.ACTIVE, false))
                .forEach(roaming -> {
                    names.add(new Named(ProductType.ROAMING, roaming.getName()));
                    names.add(new Named(ProductType.ROAMING, roaming.getCountry()));
                });
        return names.stream().filter(named -> StringUtils.hasText(named.name())).distinct().toList();
    }

    private record Named(ProductType type, String name) {
    }

    record PlanFee(String name, Integer fee) {
    }

    private record Snapshot(List<Named> names, List<String> planSeries, PlanSeriesOverview planOverview,
            Instant loadedAt) {
    }
}
