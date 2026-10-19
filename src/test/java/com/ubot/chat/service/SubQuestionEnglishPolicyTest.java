package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqRepository.EnglishWord;
import com.ubot.faq.service.FaqChangedEvent;
import com.ubot.prompt.service.SubQuestionPromptService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class SubQuestionEnglishPolicyTest {
	private final FaqRepository repository = mock(FaqRepository.class);
	private final SubQuestionPromptService prompts = mock(SubQuestionPromptService.class);
	private final SubQuestionEnglishPolicy policy = new SubQuestionEnglishPolicy(repository, prompts);

	@Test
	void checksOnlyPromptWordsNotUsedInFaq() {
		when(prompts.englishWords()).thenReturn(Set.of("faq", "custommarker"));
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of(word(1L, "faq"), word(1L, "esim")));
		policy.initialize();

		assertThat(policy.requiresSource("FAQ")).isFalse();
		assertThat(policy.requiresSource("eSIM")).isFalse();
		assertThat(policy.requiresSource("why")).isFalse();
		assertThat(policy.requiresSource("CustomMarker")).isTrue();
	}

	@Test
	void readsOnlyChangedFaqAfterInitialLoad() {
		when(prompts.englishWords()).thenReturn(Set.of("faq"));
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of());
		when(repository.findActiveEnglishWords(2L)).thenReturn(List.of(word(2L, "faq")));
		policy.initialize();
		assertThat(policy.requiresSource("faq")).isTrue();

		policy.onFaqChanged(new FaqChangedEvent(Set.of(2L)));
		assertThat(policy.requiresSource("faq")).isFalse();
		verify(repository, times(1)).findActiveEnglishWords(null);
		verify(repository).findActiveEnglishWords(2L);
	}

	@Test
	void keepsSharedWordUntilLastFaqIsRemoved() {
		when(prompts.englishWords()).thenReturn(Set.of("faq"));
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of(word(1L, "faq"), word(2L, "faq")));
		when(repository.findActiveEnglishWords(1L)).thenReturn(List.of());
		when(repository.findActiveEnglishWords(2L)).thenReturn(List.of());
		policy.initialize();
		policy.onFaqChanged(new FaqChangedEvent(Set.of(1L)));
		assertThat(policy.requiresSource("faq")).isFalse();
		policy.onFaqChanged(new FaqChangedEvent(Set.of(2L)));
		assertThat(policy.requiresSource("faq")).isTrue();
	}

	@Test
	void replacesOnlyChangedFaqWords() {
		when(prompts.englishWords()).thenReturn(Set.of("oldword", "newword", "shared"));
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of(word(1L, "oldword"), word(2L, "shared")));
		when(repository.findActiveEnglishWords(1L)).thenReturn(List.of(word(1L, "newword")));
		policy.initialize();
		policy.onFaqChanged(new FaqChangedEvent(Set.of(1L)));
		assertThat(policy.requiresSource("oldword")).isTrue();
		assertThat(policy.requiresSource("newword")).isFalse();
		assertThat(policy.requiresSource("shared")).isFalse();
	}

	@Test
	void retainsLastSnapshotWhenDatabaseReadFails() {
		when(prompts.englishWords()).thenReturn(Set.of("faq", "marker"));
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of(word(1L, "faq")));
		when(repository.findActiveEnglishWords(1L)).thenThrow(new QueryTimeoutException("temporary failure"));
		policy.initialize();
		policy.onFaqChanged(new FaqChangedEvent(Set.of(1L)));

		assertThat(policy.requiresSource("FAQ")).isFalse();
		assertThat(policy.requiresSource("marker")).isTrue();
	}

	private EnglishWord word(long faqId, String value) {
		return new EnglishWord() {
			public Long getFaqId() { return faqId; }
			public String getWord() { return value; }
		};
	}
}
