package com.ubot.chat.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.product.ProductNameIndex;
import com.ubot.ai.tool.product.ProductType;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductIntentHandlerTest {

	private final ProductNameIndex nameIndex = mock(ProductNameIndex.class);
	private final ProductIntentHandler handler = new ProductIntentHandler(nameIndex);

	@Test
	void FAQ_카테고리에_맞는_상품_도구만_붙이고_FAQ_답변_대신_도구_안내를_넣는다() {
		AnswerMaterials built = contribute("밸런스 59는 얼마예요?", faq(1L, "요금제"));

		assertThat(built.tools()).containsExactly(AiTool.PLAN_SEARCH);
		// 상품별 규칙은 상품 설명이 원본이라 FAQ 답변은 근거로 넣지 않습니다.
		assertThat(built.faqs()).isEmpty();
		assertThat(built.sections()).containsExactly(
				new ContextSection(ProductIntentHandler.SECTION_TITLE, ProductIntentHandler.TOOL_GUIDE));
	}

	@Test
	void 한쪽_FAQ만_걸려도_질문_속_종류_키워드로_다른_도구를_더한다() {
		AnswerMaterials built = contribute("요금제 종류랑 부가서비스 종류 알려줘", faq(1L, "요금제"));

		assertThat(built.tools()).containsExactlyInAnyOrder(AiTool.PLAN_SEARCH, AiTool.ADDON_SEARCH);
	}

	@Test
	void 종류를_가리키는_말이_없으면_질문_속_상품_이름으로_종류를_찾는다() {
		when(nameIndex.typesMentionedIn("5G 요금제랑 컬러링 가격")).thenReturn(Set.of(ProductType.ADDON));

		AnswerMaterials built = contribute("5G 요금제랑 컬러링 가격", faq(1L, "요금제"));

		assertThat(built.tools()).containsExactlyInAnyOrder(AiTool.PLAN_SEARCH, AiTool.ADDON_SEARCH);
	}

	@Test
	void 카테고리를_매핑할_수_없고_단서도_없으면_상품_도구를_모두_붙인다() {
		AnswerMaterials built = contribute("음악 싸게 듣는 방법", faq(1L, "통화/문자"), faq(2L, null));

		assertThat(built.tools()).containsExactlyInAnyOrder(
				AiTool.PLAN_SEARCH, AiTool.ADDON_SEARCH, AiTool.BUNDLE_SEARCH, AiTool.ROAMING_SEARCH);
	}

	@Test
	void 로밍과_결합_카테고리도_해당_도구로_바꾼다() {
		AnswerMaterials built = contribute("로밍이랑 결합", faq(1L, "해외 로밍"), faq(2L, "결합상품"));

		assertThat(built.tools()).containsExactlyInAnyOrder(AiTool.ROAMING_SEARCH, AiTool.BUNDLE_SEARCH);
	}

	private AnswerMaterials contribute(String question, FaqSearchResponseDto... faqs) {
		var materials = AnswerMaterials.builder(question);
		handler.contribute(new ChatContext(null, question, null), List.of(faqs), materials);
		return materials.build();
	}

	private static FaqSearchResponseDto faq(Long id, String category) {
		return new FaqSearchResponseDto(id, "질문", "답변", 0.9, Intent.PRODUCT_DATA, id, "질문", false, category);
	}
}
