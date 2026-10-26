package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(premium, 2));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        assertThat(searchedCondition().name()).isEqualTo("프리미엄");
        assertThat(recorder.listing()).hasValueSatisfying(listing -> assertThat(listing)
                .isInstanceOfSatisfying(ProductListing.class, found -> assertThat(found.subject()).isEqualTo("프리미엄 요금제")));
    }

    @Test
    void 모델이_이름을_넣었으면_그대로_쓴다() {
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(List.of(), 0));

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
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(cheapest, 107));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        assertThat(recorder.listing()).isEmpty();
    }

    @Test
    void 목록_요청_결과가_다_들어가지_않으면_목록_대신_조건을_고르라고_안내한다() {
        List<Plan> cheapest = List.of(plan("Ubot 실속 LTE 500MB/60분"), plan("Ubot 통화 전용"));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(cheapest, 107));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        assertThat(recorder.listing()).hasValueSatisfying(listing -> {
            assertThat(listing).isInstanceOf(ProductRefinePrompt.class);
            assertThat(listing.answer()).isEqualTo("조건에 맞는 요금제는 107가지가 있습니다.\n원하시는 조건을 선택해 주세요.");
            assertThat(listing.products()).isEmpty();
        });
        assertThat(recorder.refine()).isPresent();
    }

    @Test
    void 목록_요청이_아니거나_개수를_말했거나_버튼_조회면_안내로_바꾸지_않는다() {
        List<Plan> cheapest = List.of(plan("Ubot 실속 LTE 500MB/60분"), plan("Ubot 통화 전용"));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(cheapest, 107));

        planTools.searchPlans(null, null, null, null, null, null, null, null, toolContext);
        assertThat(recorder.listing()).isEmpty();

        var oneRecorder = new ProductDataRecorder("요금제 하나만 알려줘");
        planTools.searchPlans(null, null, null, null, null, null, null, true,
                new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, oneRecorder)));
        assertThat(oneRecorder.listing()).hasValueSatisfying(listing -> assertThat(listing).isInstanceOf(ProductListing.class));

        var refineRecorder = ProductDataRecorder.forRefine(ProductRefineSearch.LIMIT);
        planTools.searchPlans(null, null, null, null, null, null, null, true,
                new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, refineRecorder)));
        assertThat(refineRecorder.listing()).hasValueSatisfying(listing -> assertThat(listing).isInstanceOf(ProductListing.class));
    }

    @Test
    void 조건_없는_목록_요청이_결과에_다_들어가지_않으면_시리즈_개요로_답한다() {
        var overview = new PlanSeriesOverview(List.of(
                new PlanSeriesOverview.Group("프리미엄", true, 5, 89000, 129000),
                new PlanSeriesOverview.Group("실속", true, 13, 3300, 19800)));
        when(nameIndex.planOverview()).thenReturn(Optional.of(overview));
        List<Plan> cheapest = List.of(plan("Ubot 실속 LTE 500MB/60분"), plan("Ubot 통화 전용"));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(cheapest, 107));

        planTools.searchPlans(null, null, null, null, null, null, null, true, toolContext);

        // 개요의 개수·시리즈 부분은 남기고, 끝의 안내만 조건 선택 안내로 바꿉니다.
        assertThat(recorder.listing()).hasValueSatisfying(listing -> assertThat(listing.answer())
                .isEqualTo(overview.summary() + "\n\n" + ProductRefinePrompt.GUIDE));
        // 개요의 월정액 범위는 조회한 행에 없으므로 출력 검사 근거에 남아야 합니다.
        assertThat(recorder.describe()).contains("월 89,000원~129,000원");
    }

    @Test
    void 조건이_있거나_결과가_다_들어가면_시리즈_개요를_쓰지_않는다() {
        List<Plan> premium = List.of(plan("Ubot 프리미엄 89"), plan("Ubot 프리미엄 99"));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(premium, 2));

        planTools.searchPlans(null, "5G", null, null, null, null, null, true, toolContext);

        verify(nameIndex, never()).planOverview();
        assertThat(recorder.listing()).hasValueSatisfying(listing -> assertThat(listing).isInstanceOf(ProductListing.class));
    }

    @Test
    void 질문에_개수가_있으면_목록_답변과_카드를_그_개수로_줄이고_도구_결과는_그대로_둔다() {
        var oneRecorder = new ProductDataRecorder("프리미엄 요금제 하나만 알려줘");
        var oneContext = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, oneRecorder));
        List<Plan> premium = List.of(plan("Ubot 프리미엄 89"), plan("Ubot 프리미엄 99"), plan("Ubot 프리미엄 109"));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(premium, 3));

        String result = planTools.searchPlans("프리미엄", null, null, null, null, null, null, true, oneContext);

        assertThat(result).contains("Ubot 프리미엄 89", "Ubot 프리미엄 99", "Ubot 프리미엄 109");
        assertThat(oneRecorder.listing()).hasValueSatisfying(listing -> {
            assertThat(listing.products()).extracting(card -> card.name()).containsExactly("Ubot 프리미엄 89");
            assertThat(listing.answer()).startsWith("프리미엄 요금제는 3가지가 있으며, 월정액 낮은 순으로 1가지를 안내드립니다.");
        });
    }

    @Test
    void 데이터_무제한_false는_조건으로_쓰지_않는다() {
        Found<Plan> found = new Found<>(List.of(plan("Ubot 데이터 100GB")), 1);
        when(catalog.searchPlans(any(), anyInt())).thenReturn(found);

        planTools.searchPlans(null, null, null, null, 100, false, null, null, toolContext);

        assertThat(searchedCondition().dataUnlimited()).isNull();
    }

    @Test
    void 조건에_맞는_요금제가_없어도_이름에_맞는_요금제가_하나면_다른_조건을_빼고_돌려준다() {
        Found<Plan> none = new Found<>(List.of(), 0);
        Found<Plan> one = new Found<>(List.of(plan("Ubot 프리미엄 89")), 1);
        when(catalog.searchPlans(any(), anyInt())).thenReturn(none, one);

        String result = planTools.searchPlans("프리미엄 89", "5G", "GENERAL", 89000, 0, null, null, false, toolContext);

        assertThat(result).contains("Ubot 프리미엄 89");
        ArgumentCaptor<PlanCondition> conditions = ArgumentCaptor.forClass(PlanCondition.class);
        verify(catalog, times(2)).searchPlans(conditions.capture(), anyInt());
        assertThat(conditions.getAllValues().get(1))
                .isEqualTo(new PlanCondition("프리미엄 89", null, null, null, null, null, null));
    }

    @Test
    void 이름에_맞는_요금제가_여럿이면_조건에_맞는_요금제가_없다고_돌려준다() {
        Found<Plan> none = new Found<>(List.of(), 0);
        Found<Plan> several = new Found<>(List.of(plan("Ubot 프리미엄 89"), plan("Ubot 프리미엄 99")), 5);
        when(catalog.searchPlans(any(), anyInt())).thenReturn(none, several);

        String result = planTools.searchPlans("프리미엄", null, null, 50000, null, null, null, true, toolContext);

        assertThat(result).doesNotContain("Ubot 프리미엄 89");
        assertThat(recorder.cards()).isEmpty();
    }

    @Test
    void 질문에_근거가_없는_조건은_빼고_조회한다() {
        // 모델이 프롬프트의 사용자 요금제 값(5G, 일반, 129,000원, 무제한)을 조건으로 채운 상황입니다.
        var balance = new ProductDataRecorder("밸런스 요금제 알려줘");
        var context = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, balance));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(List.of(), 0));

        planTools.searchPlans("밸런스", "5G", "GENERAL", 129000, 0, true, null, true, context);

        assertThat(searchedCondition()).isEqualTo(new PlanCondition("밸런스", null, null, null, null, null, null));
    }

    @Test
    void 이전_사용자_질문에_있는_조건은_근거로_본다() {
        var followUp = new ProductDataRecorder("그중에 제일 싼 거", List.of("5G 데이터 무제한 요금제 알려줘"));
        var context = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, followUp));
        when(catalog.searchPlans(any(), anyInt())).thenReturn(new Found<>(List.of(), 0));

        planTools.searchPlans(null, "5G", "GENERAL", null, null, true, null, null, context);

        PlanCondition condition = searchedCondition();
        assertThat(condition.networkType()).isEqualTo(com.ubot.plan.enums.NetworkType.FIVE_G);
        assertThat(condition.dataUnlimited()).isTrue();
        assertThat(condition.targetGroup()).isNull();
    }

    private PlanCondition searchedCondition() {
        ArgumentCaptor<PlanCondition> condition = ArgumentCaptor.forClass(PlanCondition.class);
        // 0건이면 이름만으로 한 번 더 조회하므로 첫 조회의 조건을 봅니다.
        verify(catalog, atLeastOnce()).searchPlans(condition.capture(), anyInt());
        return condition.getAllValues().getFirst();
    }

    private static Plan plan(String name) {
        Plan plan = mock(Plan.class);
        when(plan.getName()).thenReturn(name);
        return plan;
    }
}
