package com.ubot.chat.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.context.GeneralIntentHandler;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.user.service.UserService;
import java.util.List;

/** ChatService 테스트가 공통으로 쓰는 준비물입니다. */
final class ChatTestFixtures {
	private ChatTestFixtures() {
	}

	/** 실제 핸들러로 만든 수집기입니다. 기존 테스트의 FAQ는 모두 GENERAL이라 FAQ가 그대로 전달됩니다. */
	static ChatContextCollector collector() {
		return new ChatContextCollector(List.of(
				new GeneralIntentHandler(), new StoreIntentHandler(), new UserIntentHandler(mock(UserService.class))));
	}

	/** 질문과 FAQ 목록이 같은 답변 자료와 일치하는 Mockito 인자 조건입니다. */
	static AnswerMaterials materialsFor(String question, List<FaqSearchResponseDto> faqs) {
		return argThat(materials -> materials != null
				&& question.equals(materials.question()) && faqs.equals(materials.faqs()));
	}
}
