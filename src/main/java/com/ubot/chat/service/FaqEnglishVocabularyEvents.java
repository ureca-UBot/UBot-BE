package com.ubot.chat.service;

import com.ubot.faq.service.FaqChangedEvent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** API에서 커밋된 변경을 별도 ai-worker를 포함한 각 서버에 전달합니다. */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
@Slf4j
class FaqEnglishVocabularyEvents {
	static final String CHANNEL = "ubot:faq:english-vocabulary:changed";
	private final StringRedisTemplate redis;
	private final SubQuestionEnglishPolicy policy;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(FaqChangedEvent event) {
		String ids = event.faqIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
		try {
			redis.convertAndSend(CHANNEL, ids);
		} catch (org.springframework.dao.DataAccessException exception) {
			log.warn("FAQ 영문 허용 목록 변경 알림을 전송하지 못했습니다: FAQIDs={}", ids, exception);
		}
	}

	@Bean
	RedisMessageListenerContainer faqEnglishVocabularyListener(RedisConnectionFactory connectionFactory) {
		var container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(new VocabularyListener(), new ChannelTopic(CHANNEL));
		return container;
	}

	class VocabularyListener implements MessageListener, SubscriptionListener {
		@Override
		public void onMessage(Message message, byte[] pattern) {
			receive(new String(message.getBody(), StandardCharsets.UTF_8));
		}

		@Override
		public void onChannelSubscribed(byte[] channel, long count) {
			policy.initialize();
		}
	}

	void receive(String message) {
		try {
			Set<Long> ids = Arrays.stream(message.split(",")).map(Long::valueOf).collect(Collectors.toSet());
			if (ids.stream().anyMatch(id -> id < 1)) {
				throw new IllegalArgumentException("FAQ ID는 양수여야 합니다.");
			}
			policy.onFaqChanged(new FaqChangedEvent(ids));
		} catch (IllegalArgumentException exception) {
			log.warn("FAQ 영문 허용 목록 변경 알림의 ID가 올바르지 않습니다", exception);
		}
	}
}
