package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.tool.product.ProductNameIndex;
import com.ubot.ai.tool.product.ProductType;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 상품(요금제·부가서비스·결합·로밍) 정보 질문입니다. 가격·제공량·목록과 상품별 규칙(가입 대상·포함 혜택·약정 여부)은
 * DB가 원본이라 LLM이 상품 조회 도구로 가져옵니다. 사용자 정보·매장 의도처럼 FAQ 답변은 근거로 넣지 않고,
 * FAQ는 의도 판정과 붙일 도구를 고르는 데만 씁니다.
 * 붙일 도구는 걸린 FAQ의 카테고리, 질문 속 종류 키워드, 질문 속 상품 이름·국가를 합쳐 정하고, 하나도 못 찾으면 네 종류를 모두 붙입니다.
 * 카테고리만 보면 "요금제랑 부가서비스 종류"처럼 한쪽 FAQ만 걸린 질문에서 다른 종류를 놓치기 때문입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIntentHandler implements IntentHandler {

	static final String SECTION_TITLE = "상품 정보";
	// "조회하지 마세요" 같은 금지형 문장은 작은 모델이 도구 전체를 쓰지 말라는 뜻으로 받아들이므로 긍정형으로만 안내합니다.
	static final String TOOL_GUIDE = "상품 가격·제공량·가입 조건·목록은 이 자료에 없습니다. 답하기 전에 상품 조회 도구를 호출해 확인하세요.";

	private final ProductNameIndex productNameIndex;

	@Override
	public Intent intent() {
		return Intent.PRODUCT_DATA;
	}

	@Override
	public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
		Set<ProductType> types = productTypes(context.question(), results);
		types.forEach(type -> materials.enableTool(type.tool()));
		materials.addSection(new ContextSection(SECTION_TITLE, TOOL_GUIDE));
		log.info("상품 조회 도구를 붙였습니다: 종류={}", types);
	}

	private Set<ProductType> productTypes(String question, List<FaqSearchResponseDto> results) {
		Set<ProductType> types = EnumSet.noneOf(ProductType.class);
		for (FaqSearchResponseDto faq : results) {
			Optional<ProductType> type = ProductType.fromCategory(faq.categoryName());
			if (type.isPresent()) {
				types.add(type.get());
			} else {
				log.warn("상품 종류에 대응하지 않는 PRODUCT_DATA FAQ 카테고리입니다: FAQ ID={}, 카테고리={}",
						faq.faqId(), faq.categoryName());
			}
		}
		Arrays.stream(ProductType.values()).filter(type -> type.isMentionedIn(question)).forEach(types::add);
		types.addAll(productNameIndex.typesMentionedIn(question));
		return types.isEmpty() ? EnumSet.allOf(ProductType.class) : types;
	}
}
