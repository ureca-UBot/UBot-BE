package com.ubot.chat.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.context.GeneralIntentHandler;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.service.FaqVectorService;
import com.pgvector.PGvector;
import com.ubot.user.service.UserService;
import java.util.List;

/** ChatService 테스트가 공통으로 쓰는 준비물입니다. */
final class ChatTestFixtures {
	static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});
	private ChatTestFixtures() {
	}

	static FaqSemanticCacheService disabledCache() {
		return new FaqSemanticCacheService(new FaqCacheProperties());
	}

	/** 검색 이외의 채팅 테스트에서 기존 FAQ 검색 목을 새 질문 벡터 반환 계약에 연결합니다. */
	static FaqVectorService vectorService() {
		return mock(FaqVectorService.class, invocation -> {
			if (invocation.getMethod().getName().equals("searchWithVector")) {
				String question = invocation.getArgument(0);
				com.ubot.faq.enums.Intent intent = invocation.getArgument(1);
				int topK = invocation.getArgument(2);
				FaqVectorService self = (FaqVectorService) invocation.getMock();
				List<FaqSearchResponseDto> faqs = intent == null
						? self.getSimilarList(question, topK)
						: self.getSimilarListByIntent(question, intent, topK);
				return new FaqVectorService.SearchResult(faqs, QUERY_VECTOR);
			}
			return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
		});
	}

	/** 검색 분기와 무관한 테스트에서는 FAQ 답변을 그대로 통과시킵니다. */
	static FaqSentenceFilterService passthroughSentenceFilter() {
		return mock(FaqSentenceFilterService.class, invocation -> {
			List<FaqSearchResponseDto> faqs = invocation.getArgument(1);
			return faqs.stream().map(faq -> new FaqSentenceFilterService.MatchedFaq(faq, faq.answer())).toList();
		});
	}

	/** 실제 핸들러로 만든 수집기입니다. 기존 테스트의 FAQ는 모두 GENERAL이라 FAQ가 그대로 전달됩니다. */
	static ChatContextCollector collector() {
		return collector(mock(NearbyStoreSearcher.class));
	}

	/** 내 위치 기준 매장 조회 결과를 직접 정할 때 씁니다. */
	static ChatContextCollector collector(NearbyStoreSearcher nearbyStoreSearcher) {
		return new ChatContextCollector(List.of(
				new GeneralIntentHandler(), new StoreIntentHandler(nearbyStoreSearcher),
				new UserIntentHandler(mock(UserService.class))));
	}

	/** 질문과 FAQ 목록이 같은 답변 자료와 일치하는 Mockito 인자 조건입니다. */
	static AnswerMaterials materialsFor(String question, List<FaqSearchResponseDto> faqs) {
		return argThat(materials -> materials != null
				&& question.equals(materials.question()) && faqs.equals(materials.faqs()));
	}
}
