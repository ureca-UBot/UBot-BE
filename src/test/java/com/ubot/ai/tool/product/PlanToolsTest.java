package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ai.tool.product.ProductCatalogQuery.Found;
import com.ubot.ai.tool.product.ProductCatalogQuery.PlanCondition;
import com.ubot.plan.entity.Plan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

/** 모델이 빠뜨린 시리즈 이름을 질문에서 채우는지, 걸러야 할 질문을 전체 조회로 목록 답변하지 않는지 확인합니다. */
class PlanToolsTest {

    private static final String QUESTION = "프리미엄 요금제 종류 알려줘";

    private final ProductCatalogQuery catalog = mock(ProductCatalogQuery.class);
    private final ProductNameIndex nameIndex = mock(ProductNameIndex.class);
    private final PlanTools planTools = new PlanTools(catalog, nameIndex, 25);
    private final ProductDataRecorder recorder = new ProductDataRecorder(QUESTION);
    private final ToolContext toolContext = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, recorder));

    @Test
    void 모델이_이름을_비우면_질문의_시리즈_이름으로_조회한다() {
        when(nameIndex.planSeriesMentionedIn(QUESTION)).thenReturn(Optional.of("프리미엄"));
        List<Plan> premium = List.of(plan("Ubot 프리미엄 89"), plan("Ubot 프리미엄 99"));
        when(catalog.searchPlans(any())).thenReturn(new Found<>(premium, 2));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        assertThat(searchedCondition().name()).isEqualTo("프리미엄");
        assertThat(recorder.listing()).hasValueSatisfying(listing ->
                assertThat(listing.subject()).isEqualTo("프리미엄 요금제"));
    }

    @Test
    void 모델이_이름을_넣었으면_그대로_쓴다() {
        when(catalog.searchPlans(any())).thenReturn(new Found<>(List.of(), 0));

        planTools.searchPlans("밸런스", null, null, null, null, null, null, null, toolContext);

        assertThat(searchedCondition().name()).isEqualTo("밸런스");
        verify(nameIndex, never()).planSeriesMentionedIn(any());
    }

    @Test
    void 질문에_시리즈_이름이_있는데_조건_없이_전체를_조회했으면_목록_답변을_만들지_않는다() {
        // 시리즈가 둘 이상이라 하나로 정하지 못한 경우입니다.
        when(nameIndex.planSeriesMentionedIn(QUESTION)).thenReturn(Optional.empty());
        when(nameIndex.mentionsPlanSeries(QUESTION)).thenReturn(true);
        List<Plan> cheapest = List.of(plan("Ubot 실속 LTE 500MB/60분"), plan("Ubot 통화 전용"));
        when(catalog.searchPlans(any())).thenReturn(new Found<>(cheapest, 107));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        assertThat(recorder.listing()).isEmpty();
    }

    @Test
    void 질문에_시리즈_이름이_없으면_전체_목록도_목록_답변으로_만든다() {
        List<Plan> cheapest = List.of(plan("Ubot 실속 LTE 500MB/60분"), plan("Ubot 통화 전용"));
        when(catalog.searchPlans(any())).thenReturn(new Found<>(cheapest, 107));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        assertThat(recorder.listing()).hasValueSatisfying(listing ->
                assertThat(listing.subject()).isEqualTo("조건에 맞는 요금제"));
    }

    private PlanCondition searchedCondition() {
        ArgumentCaptor<PlanCondition> condition = ArgumentCaptor.forClass(PlanCondition.class);
        verify(catalog).searchPlans(condition.capture());
        return condition.getValue();
    }

    private static Plan plan(String name) {
        Plan plan = mock(Plan.class);
        when(plan.getName()).thenReturn(name);
        return plan;
    }
}
