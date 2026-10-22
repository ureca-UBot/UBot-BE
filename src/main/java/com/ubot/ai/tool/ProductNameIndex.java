package com.ubot.ai.tool;

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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

    private Snapshot current() {
        Snapshot cached = snapshot;
        Instant now = clock.instant();
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(now)) {
            return cached;
        }
        try {
            Snapshot loaded = new Snapshot(load(), now);
            snapshot = loaded;
            return loaded;
        } catch (RuntimeException exception) {
            // 이름 보강은 도구 선택을 돕는 단계라, 실패해도 FAQ 카테고리와 키워드로 고른 도구로 진행합니다.
            log.warn("상품 이름 목록을 불러오지 못했습니다", exception);
            return cached != null ? cached : new Snapshot(List.of(), now);
        }
    }

    private List<Named> load() {
        List<Named> names = new ArrayList<>();
        planRepository.findAll(ProductSpecification.filter(null, MasterStatus.ACTIVE, false))
                .forEach(plan -> names.add(new Named(ProductType.PLAN, plan.getName())));
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

    private record Snapshot(List<Named> names, Instant loadedAt) {
    }
}
