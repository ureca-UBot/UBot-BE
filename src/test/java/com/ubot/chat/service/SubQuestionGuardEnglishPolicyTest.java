package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.ConversationTurn;
import com.ubot.chat.service.QuestionMorphology.Morph;
import com.ubot.chat.service.SubQuestionGuard.Reason;
import com.ubot.faq.repository.FaqRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 형태소 결과만 고정하여 실제 영문 정책·가드의 출처 비교를 확인합니다. Kiwi·LLM은 호출하지 않습니다. */
class SubQuestionGuardEnglishPolicyTest {
	private SubQuestionGuard guard;

	@BeforeEach
	void setUp() {
		var repository = mock(FaqRepository.class);
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of(new FaqRepository.EnglishWord() {
			public Long getFaqId() { return 1L; }
			public String getWord() { return "esim"; }
		}));
		var policy = new SubQuestionEnglishPolicy(repository);
		policy.initialize();
		var morphology = mock(QuestionMorphology.class);
		when(morphology.analyze(anyString())).thenAnswer(invocation -> {
			String text = invocation.getArgument(0);
			return List.of(new Morph(text, "SL", 0, text.length(), text));
		});
		when(morphology.words(anyString())).thenReturn(List.of());
		guard = new SubQuestionGuard(morphology, new OriginalQuestionSplitter(morphology), policy);
	}

	@Test
	void restoresOriginalForWhyObservedInActualJudgmentOutputs() {
		for (String question : List.of("사진이나 동영상 문자(엠엠에스)가 안 보내져요",
				"휴대폰에 '서비스 지역이 아닙니다'라고 나와요")) {
			var result = guard.check(question, List.of(), List.of(question + " why?"));

			assertThat(result.searchQueries()).containsExactly(question);
			assertThat(result.reasons()).containsExactly(Reason.PROMPT_LEAK);
		}
	}

	@Test
	void permitsFaqEnglishAbsentFromOriginal() {
		var result = guard.check("이심 개통 방법은?", List.of(), List.of("eSIM 개통 방법은?"));

		assertThat(result.searchQueries()).containsExactly("eSIM 개통 방법은?");
		assertThat(result.reasons()).isEmpty();
	}

	@Test
	void permitsUnregisteredEnglishPresentInOriginalIgnoringCase() {
		var result = guard.check("MyBrand 휴대폰 개통 방법은?", List.of(), List.of("MYBRAND 휴대폰 개통 방법은?"));

		assertThat(result.searchQueries()).containsExactly("MYBRAND 휴대폰 개통 방법은?");
		assertThat(result.reasons()).isEmpty();
	}

	@Test
	void permitsUnregisteredEnglishPresentInPreviousConversation() {
		var history = List.of(new ConversationTurn("MyBrand 휴대폰을 쓰고 있어요", "설정을 확인해 주세요."));
		var result = guard.check("그 휴대폰 개통 방법은?", history, List.of("MyBrand 휴대폰 개통 방법은?"));

		assertThat(result.searchQueries()).containsExactly("MyBrand 휴대폰 개통 방법은?");
		assertThat(result.reasons()).isEmpty();
	}

	@Test
	void blocksUnknownEnglishEvenAlongsideRegisteredFaqEnglish() {
		var result = guard.check("이심 개통 방법은?", List.of(), List.of("eSIM 개통 방법은? why?"));

		assertThat(result.searchQueries()).containsExactly("이심 개통 방법은?");
		assertThat(result.reasons()).containsExactly(Reason.PROMPT_LEAK);
	}
}
