package com.ubot.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.ai.dto.ProductDataCards;
import com.ubot.ai.tool.product.ProductRefine;
import com.ubot.ai.tool.product.ProductRefineSearch;
import com.ubot.ai.tool.product.ProductType;
import com.ubot.common.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("버튼 조건 상품 조회 컨트롤러 테스트")
class ChatProductSearchControllerTest {

	ProductRefineSearch productRefineSearch = mock(ProductRefineSearch.class);
	MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ChatProductSearchController(productRefineSearch))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("고른 조건과 검색어로 조회해 답변 문장과 카드를 돌려준다")
	void returnsAnswerAndCards() throws Exception {
		when(productRefineSearch.search(ProductType.PLAN, "밸런스", Map.of("data", "10")))
				.thenReturn(new ProductRefineSearch.Result("밸런스 요금제는 다음 9가지가 있습니다.", new ProductDataCards(List.of())));

		mvc.perform(post("/chat/product-search").contentType("application/json")
						.content("{\"type\":\"PLAN\",\"keyword\":\"밸런스\",\"conditions\":{\"data\":\"10\"}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.answer").value("밸런스 요금제는 다음 9가지가 있습니다."))
				.andExpect(jsonPath("$.data.productData.products").isArray());
	}

	@Test
	@DisplayName("조건이 올바르지 않으면 400과 CHAT-027을 돌려준다")
	void invalidConditionIsBadRequest() throws Exception {
		when(productRefineSearch.search(eq(ProductType.PLAN), any(), any()))
				.thenThrow(new ProductRefineSearch.InvalidConditionException("테스트"));

		mvc.perform(post("/chat/product-search").contentType("application/json")
						.content("{\"type\":\"PLAN\",\"conditions\":{\"networkType\":\"6G\"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CHAT-027"));
	}

	@Test
	@DisplayName("단계 선택지마다 결과가 있는지 돌려주고, 단계가 잘못되면 400을 돌려준다")
	void optionsReturnAvailability() throws Exception {
		when(productRefineSearch.options(ProductType.PLAN, "밸런스", Map.of("networkType", "FIVE_G"), "data"))
				.thenReturn(new ProductRefine.Availability("data", true,
						List.of(new ProductRefine.OptionAvailability("10", true),
								new ProductRefine.OptionAvailability("100", false))));
		when(productRefineSearch.options(ProductType.PLAN, null, null, "category"))
				.thenThrow(new ProductRefineSearch.InvalidConditionException("테스트"));

		mvc.perform(post("/chat/product-search/options").contentType("application/json")
						.content("{\"type\":\"PLAN\",\"keyword\":\"밸런스\",\"conditions\":{\"networkType\":\"FIVE_G\"},\"key\":\"data\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.anyAvailable").value(true))
				.andExpect(jsonPath("$.data.options[1].available").value(false));
		mvc.perform(post("/chat/product-search/options").contentType("application/json")
						.content("{\"type\":\"PLAN\",\"key\":\"category\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CHAT-027"));
	}

	@Test
	@DisplayName("상품 종류가 없으면 조회하지 않고 400을 돌려준다")
	void missingTypeIsBadRequest() throws Exception {
		mvc.perform(post("/chat/product-search").contentType("application/json").content("{\"keyword\":\"밸런스\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(productRefineSearch);
	}
}
