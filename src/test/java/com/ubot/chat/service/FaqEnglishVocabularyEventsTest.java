package com.ubot.chat.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ubot.faq.service.FaqChangedEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class FaqEnglishVocabularyEventsTest {
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final SubQuestionEnglishPolicy policy = mock(SubQuestionEnglishPolicy.class);
	private final FaqEnglishVocabularyEvents events = new FaqEnglishVocabularyEvents(redis, policy);

	@Test
	void broadcastsOnlyChangedIds() {
		events.publish(new FaqChangedEvent(Set.of(4L, 2L)));
		verify(redis).convertAndSend(FaqEnglishVocabularyEvents.CHANNEL, "2,4");
	}

	@Test
	void refreshesIdsReceivedFromAnotherServer() {
		events.receive("2,4");
		verify(policy).onFaqChanged(new FaqChangedEvent(Set.of(2L, 4L)));
	}

	@Test
	void loadsAtSubscriptionAndResynchronizesOnReconnect() {
		var listener = events.new VocabularyListener();
		listener.onChannelSubscribed(new byte[0], 1);
		verify(policy).initialize();
		listener.onChannelSubscribed(new byte[0], 1);
		verify(policy, times(2)).initialize();
	}

	@Test
	void ignoresInvalidMessages() {
		events.receive("not-an-id");
		verifyNoInteractions(policy);
	}
}
