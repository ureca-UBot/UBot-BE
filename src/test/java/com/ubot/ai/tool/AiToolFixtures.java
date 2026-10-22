package com.ubot.ai.tool;

import static org.mockito.Mockito.mock;

import com.ubot.billing.service.BillingService;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;

/** 매장 도구만 쓰는 테스트에서 AiToolRegistry를 만들 때 씁니다. 가입 정보 도구는 조회하지 않는 빈 서비스로 채웁니다. */
public final class AiToolFixtures {

    private AiToolFixtures() {
    }

    public static AiToolRegistry registry(StoreTools storeTools) {
        return new AiToolRegistry(storeTools, userDataTools());
    }

    public static UserDataTools userDataTools() {
        return new UserDataTools(mock(SubscriptionService.class), mock(UsageService.class),
                mock(BillingService.class), UserDataFixtures.CLOCK);
    }
}
