package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 검색된 FAQ의 intent 라벨별로 핸들러를 실행해 LLM 요청 한 번에 쓸 답변 자료를 모읍니다. */
@Component
public class ChatContextCollector {
	private final Map<Intent, IntentHandler> handlers;

	public ChatContextCollector(List<IntentHandler> handlerList) {
		Map<Intent, IntentHandler> registered = new EnumMap<>(Intent.class);
		for (IntentHandler handler : handlerList) {
			if (registered.putIfAbsent(handler.intent(), handler) != null) {
				throw new IllegalStateException("같은 intent의 핸들러가 여러 개입니다: " + handler.intent());
			}
		}
		// 새 intent를 추가하고 핸들러를 빠뜨리면 요청 중이 아니라 기동 시 실패하게 합니다.
		List<Intent> missing = Arrays.stream(Intent.values()).filter(intent -> !registered.containsKey(intent)).toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException("핸들러가 없는 intent가 있습니다: " + missing);
		}
		this.handlers = Collections.unmodifiableMap(registered);
	}

	/** 질문을 나누지 않았을 때입니다. 원문을 하위 질문 하나로 봅니다. */
	public AnswerMaterials collect(ChatContext context, List<FaqSearchResponseDto> filteredResults) {
		return collectBySubQuestion(context, List.of(new SubQuestionFaqs(context.question(), filteredResults, context.question())));
	}

	/**
	 * 하위 질문마다 찾은 FAQ로 답변 자료를 모읍니다. 핸들러는 모든 하위 질문의 FAQ를 합쳐 intent마다 한 번만 실행하므로,
	 * 여러 하위 질문에서 같은 intent가 나와도 사용자 정보 조회 같은 작업이 반복되지 않습니다.
	 * 그다음 핸들러가 참고 자료로 넣은 FAQ를 하위 질문별로 다시 묶어, 프롬프트에서 질문마다 근거를 나눠 보여 줄 수 있게 합니다.
	 */
	public AnswerMaterials collectBySubQuestion(ChatContext context, List<SubQuestionFaqs> subQuestions) {
		// 같은 FAQ가 여러 하위 질문에서 걸려도 핸들러에는 한 번만 넘깁니다. 처음 나온 순서(하위 질문 순서, 그 안의 유사도 순서)를 유지합니다.
		Map<Long, FaqSearchResponseDto> distinct = new LinkedHashMap<>();
		subQuestions.forEach(subQuestion -> subQuestion.faqs().forEach(faq -> distinct.putIfAbsent(faq.faqId(), faq)));
		return collectBySubQuestion(context, subQuestions, List.copyOf(distinct.values()));
	}

	/**
	 * 공통 FAQ 목록(commonFaqs)을 호출한 쪽이 만들어 넘길 때입니다. commonFaqs에는 같은 FAQ가 한 번씩만 들어 있어야 하고,
	 * 하위 질문마다 답변 문장을 다르게 고른 FAQ는 그 문장들을 합친 것이어야 합니다.
	 * 그래야 공통 목록을 쓰는 프롬프트·출력 검사가 하위 질문별 묶음과 같은 근거를 봅니다.
	 */
	public AnswerMaterials collectBySubQuestion(
			ChatContext context, List<SubQuestionFaqs> subQuestions, List<FaqSearchResponseDto> commonFaqs) {
		// 유사도 순서를 유지한 채 intent별로 묶습니다. 같은 intent가 여러 번 걸려도 핸들러는 한 번만 실행됩니다.
		Map<Intent, List<FaqSearchResponseDto>> resultsByIntent = commonFaqs.stream()
				.collect(Collectors.groupingBy(FaqSearchResponseDto::intent, LinkedHashMap::new, Collectors.toList()));

		AnswerMaterials.Builder materials = AnswerMaterials.builder(context.question());
		resultsByIntent.forEach((intent, results) -> handlers.get(intent).contribute(context, results, materials));

		// 핸들러가 넣은 FAQ만 하위 질문별로 남깁니다. 찾은 FAQ가 없는 하위 질문도 남겨 LLM이 그 부분을 답할 수 없다고 알리게 합니다.
		Set<Long> includedFaqIds = materials.build().faqs().stream()
				.map(FaqSearchResponseDto::faqId)
				.collect(Collectors.toSet());
		materials.subQuestions(subQuestions.stream()
				.map(subQuestion -> new SubQuestionFaqs(subQuestion.searchQuery(), subQuestion.faqs().stream()
						.filter(faq -> includedFaqIds.contains(faq.faqId()))
						.toList(), subQuestion.userText()))
				.toList());
		return materials.build();
	}
}
