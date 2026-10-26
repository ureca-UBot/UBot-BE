package com.ubot.ai.tool.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.tool.product.ProductCatalogQuery.Found;
import com.ubot.bundle.entity.BundleProduct;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 결합 상품 도구가 모델이 넣은 조건을 질문에 맞게 고쳐 조회하는지 확인합니다. */
class BundleToolsTest {

    private final ProductCatalogQuery catalog = mock(ProductCatalogQuery.class);
    private final ProductNameIndex nameIndex = mock(ProductNameIndex.class);

    @Test
    void 결합_종류를_말하지_않았으면_모델이_넣은_종류를_빼고_회선_수로만_찾는다() {
        var bundleTools = new BundleTools(catalog, nameIndex);
        var context = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, new ProductDataRecorder("3인 결합 상품 알려줘")));
        when(catalog.searchBundles(any(), any(), any(), anyInt())).thenReturn(new Found<BundleProduct>(List.of(), 0));

        bundleTools.searchBundles("3인", "FAMILY", 3, true, context);

        verify(catalog).searchBundles("3인", null, 3, ProductCatalogQuery.LIMIT);
    }
}
