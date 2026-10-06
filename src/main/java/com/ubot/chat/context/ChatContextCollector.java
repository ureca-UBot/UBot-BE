package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	public AnswerMaterials collect(ChatContext context, List<FaqSearchResponseDto> filteredResults) {
		// 유사도 순서를 유지한 채 intent별로 묶습니다. 같은 intent가 여러 번 걸려도 핸들러는 한 번만 실행됩니다.
		Map<Intent, List<FaqSearchResponseDto>> resultsByIntent = filteredResults.stream()
				.collect(Collectors.groupingBy(FaqSearchResponseDto::intent, LinkedHashMap::new, Collectors.toList()));

		AnswerMaterials.Builder materials = AnswerMaterials.builder(context.question());
		resultsByIntent.forEach((intent, results) -> handlers.get(intent).contribute(context, results, materials));
		return materials.build();
	}
}
