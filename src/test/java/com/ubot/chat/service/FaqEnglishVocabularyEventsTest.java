package com.ubot.chat.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.faq.service.FaqChangedEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.RedisListenerExecutionFailedException;

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
	void loadsVocabularyAndKeepsRetryingWhenRedisIsDownAtStartup() {
		var container = mock(RedisMessageListenerContainer.class);
		doThrow(new RedisListenerExecutionFailedException("Redis 연결 실패")).when(container).start();
		var context = mock(ApplicationContext.class);
		when(context.getBean("faqEnglishVocabularyListener", RedisMessageListenerContainer.class)).thenReturn(container);

		// 구독 실패가 기동 이벤트 밖으로 번지지 않고, 목록은 DB에서 바로 읽습니다.
		events.startListening(new ContextRefreshedEvent(context));
		events.startListening(new ContextRefreshedEvent(context));

		verify(policy, times(1)).initialize();
		verify(container, timeout(2000)).stop();
		events.stopRetrying();
	}

	@Test
	void ignoresInvalidMessages() {
		events.receive("not-an-id");
		verifyNoInteractions(policy);
	}
}
