package com.ubot.chat.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.product.ProductNameIndex;
import com.ubot.ai.tool.UserDataSummarizer;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.context.GeneralIntentHandler;
import com.ubot.chat.context.ProductIntentHandler;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.chat.repository.ConversationTurnRepository;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.SubQuestionPromptService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.service.FaqVectorService;
import com.pgvector.PGvector;
import com.ubot.user.service.UserService;
import com.ubot.billing.service.BillingService;
import com.ubot.contract.service.ContractService;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import java.time.Clock;
import java.util.List;

/** ChatService 테스트가 공통으로 쓰는 준비물입니다. */
final class ChatTestFixtures {
	static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});

	/** 기존 단위 테스트에 추가된 후보 선별·프로필 의존성을 모의 객체로 전달합니다. */
	static ChatAnswerProcessor processor(com.ubot.faq.retrieval.FaqRetrievalService retrieval,
			com.ubot.ai.service.AiService ai,
			ChatContextCollector collector, SubQuestionPlanner planner, FaqSemanticCacheService cache,
			FaqSentenceFilterService sentenceFilter, ChatInputGuardService inputGuard) {
		var profile = mock(com.ubot.embedding.service.EmbeddingProfileService.class);
		return new ChatAnswerProcessor(retrieval, ai, collector, planner, cache, sentenceFilter,
				inputGuard, mock(com.ubot.ai.service.FaqGapCandidateSelector.class), profile);
	}

	private ChatTestFixtures() {
	}

	static ChatInputGuardService inputGuard() {
		return inputGuard(mock(ForbiddenWordFilterService.class));
	}

	static ChatInputGuardService inputGuard(ForbiddenWordFilterService filter) {
		return new ChatInputGuardService(filter, new ChatPromptInjectionService());
	}

	/** 입력 가드와 무관한 질문 분리 단위 검사에서는 입력을 그대로 전달합니다. */
	static ChatInputGuardService passthroughInputGuard() {
		return mock(ChatInputGuardService.class, invocation -> invocation.getArgument(0));
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
				new UserIntentHandler(mock(UserDataSummarizer.class)), productIntentHandler()));
	}

	/** 질문 속 상품 이름은 찾지 않는(이름 목록이 빈) 상품 정보 핸들러입니다. */
	static ProductIntentHandler productIntentHandler() {
		return new ProductIntentHandler(mock(ProductNameIndex.class));
	}

	/** 회원 정보는 users로 조회하고 이용 중인 회선은 없는 실제 가입 정보 핸들러입니다. */
	static UserIntentHandler userIntentHandler(UserService users) {
		return new UserIntentHandler(new UserDataSummarizer(users, mock(SubscriptionService.class),
				mock(UsageService.class), mock(BillingService.class), mock(ContractService.class),
				Clock.systemDefaultZone()));
	}

	/** 판정 LLM을 부르지 않고 원문을 하위 질문 하나로 쓰는 정리기입니다. */
	static SubQuestionPlanner originalSubQuestions() {
		return new SubQuestionPlanner(false, mock(ConversationTurnRepository.class),
				mock(SubQuestionPromptService.class), mock(LlmService.class), mock(SubQuestionGuard.class),
				mock(QuestionRouter.class), mock(MultiTurnDetector.class), inputGuard());
	}

	/** 질문과 FAQ 목록이 같은 답변 자료와 일치하는 Mockito 인자 조건입니다. */
	static AnswerMaterials materialsFor(String question, List<FaqSearchResponseDto> faqs) {
		return argThat(materials -> materials != null
				&& question.equals(materials.question()) && faqs.equals(materials.faqs()));
	}
}
