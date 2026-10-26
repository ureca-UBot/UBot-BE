package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.addon.entity.AddonService;
import com.ubot.ai.tool.product.ProductCatalogQuery.Found;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 부가서비스 도구가 이름으로 찾지 못한 상품을 상품 이름 사전으로 다시 찾는지 확인합니다. */
class AddonToolsTest {

    private final ProductCatalogQuery catalog = mock(ProductCatalogQuery.class);
    private final ProductNameIndex nameIndex = mock(ProductNameIndex.class);

    @Test
    void 부가서비스_이름으로_찾지_못하면_그_안에_든_상품_이름으로_다시_찾는다() {
        var addonTools = new AddonTools(catalog, nameIndex);
        var context = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, new ProductDataRecorder("V컬러링 월 요금 알려줘")));
        AddonService coloring = mock(AddonService.class);
        when(coloring.getName()).thenReturn("Ubot 컬러링");
        when(coloring.getMonthlyFee()).thenReturn(990);
        when(catalog.searchAddons(eq("V컬러링"), isNull(), anyInt())).thenReturn(new Found<>(List.of(), 0));
        when(catalog.searchAddons(eq("Ubot 컬러링"), isNull(), anyInt())).thenReturn(new Found<>(List.of(coloring), 1));
        when(nameIndex.namesMentionedIn("V컬러링", ProductType.ADDON)).thenReturn(List.of("Ubot 컬러링"));

        String result = addonTools.searchAddons("V컬러링", null, null, context);

        assertThat(result).contains("Ubot 컬러링 | 월 요금 990원");
    }
}
