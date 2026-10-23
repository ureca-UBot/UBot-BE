package com.ubot.ai.tool;

import static org.mockito.Mockito.mock;

import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.ai.tool.product.AddonTools;
import com.ubot.ai.tool.product.BundleTools;
import com.ubot.ai.tool.product.PlanTools;
import com.ubot.ai.tool.product.ProductCatalogQuery;
import com.ubot.ai.tool.product.ProductNameIndex;
import com.ubot.ai.tool.product.RoamingTools;
import com.ubot.billing.service.BillingService;
import com.ubot.bundle.repository.BundleProductRepository;
import com.ubot.plan.repository.PlanRepository;
import com.ubot.roaming.repository.RoamingProductRepository;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;

/**
 * 매장 도구만 쓰는 테스트에서 AiToolRegistry를 만들 때 씁니다.
 * 가입 정보 도구와 상품 조회 도구는 조회하지 않는 빈 서비스·저장소로 채웁니다.
 */
public final class AiToolFixtures {

    private AiToolFixtures() {
    }

    public static AiToolRegistry registry(StoreTools storeTools) {
        ProductCatalogQuery catalog = productCatalogQuery();
        return new AiToolRegistry(storeTools, userDataTools(), new PlanTools(catalog, mock(ProductNameIndex.class), 25), new AddonTools(catalog),
                new BundleTools(catalog), new RoamingTools(catalog));
    }

    public static UserDataTools userDataTools() {
        return new UserDataTools(mock(SubscriptionService.class), mock(UsageService.class),
                mock(BillingService.class), UserDataFixtures.CLOCK);
    }

    public static ProductCatalogQuery productCatalogQuery() {
        return new ProductCatalogQuery(mock(PlanRepository.class), mock(AddonServiceRepository.class),
                mock(BundleProductRepository.class), mock(RoamingProductRepository.class));
    }
}
