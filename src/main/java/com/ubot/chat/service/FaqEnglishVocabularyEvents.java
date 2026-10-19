package com.ubot.chat.service;

import com.ubot.faq.service.FaqChangedEvent;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
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
	private static final long INITIAL_RETRY_SECONDS = 5;
	private static final long MAX_RETRY_SECONDS = 60;
	private final StringRedisTemplate redis;
	private final SubQuestionEnglishPolicy policy;
	private final AtomicBoolean listening = new AtomicBoolean();
	private final ScheduledExecutorService subscriber = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "faq-english-vocabulary-subscriber");
		thread.setDaemon(true);
		return thread;
	});

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(FaqChangedEvent event) {
		String ids = event.faqIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
		try {
			redis.convertAndSend(CHANNEL, ids);
		} catch (org.springframework.dao.DataAccessException exception) {
			log.warn("FAQ 영문 허용 목록 변경 알림을 전송하지 못했습니다: FAQIDs={}", ids, exception);
		}
	}

	/**
	 * 컨테이너는 시작할 때 첫 구독을 기다리고, Redis에 연결하지 못하면 예외를 던져 컨텍스트 기동을 실패시킵니다.
	 * 영문 허용 목록 갱신은 부가 기능이라 서버 기동을 Redis에 묶지 않도록 자동 시작을 끄고 {@link #startListening}에서 시작합니다.
	 */
	@Bean
	RedisMessageListenerContainer faqEnglishVocabularyListener(RedisConnectionFactory connectionFactory) {
		var container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(new VocabularyListener(), new ChannelTopic(CHANNEL));
		container.setAutoStartup(false);
		return container;
	}

	/**
	 * 기동이 끝나면 DB에서 허용 목록을 먼저 읽고, 변경 알림 구독은 별도 스레드에서 시작합니다.
	 * Redis가 없어도 목록은 채워지고, 구독은 연결될 때까지 간격을 늘려 가며 다시 시도합니다.
	 */
	@EventListener(ContextRefreshedEvent.class)
	public void startListening(ContextRefreshedEvent event) {
		if (!listening.compareAndSet(false, true)) {
			return;
		}
		policy.initialize();
		RedisMessageListenerContainer container =
				event.getApplicationContext().getBean("faqEnglishVocabularyListener", RedisMessageListenerContainer.class);
		subscriber.execute(() -> subscribe(container, INITIAL_RETRY_SECONDS));
	}

	private void subscribe(RedisMessageListenerContainer container, long retrySeconds) {
		try {
			container.start();
			log.info("FAQ 영문 허용 목록 변경 알림 구독을 시작했습니다");
		} catch (RuntimeException exception) {
			// 실패한 시작은 started 표시만 남으므로 멈춘 뒤 다시 시작해야 새로 구독을 시도합니다.
			container.stop();
			if (retrySeconds == INITIAL_RETRY_SECONDS) {
				log.warn("FAQ 영문 허용 목록 변경 알림을 구독하지 못해 다시 시도합니다. 그동안은 기동 시 읽은 목록을 씁니다", exception);
			}
			if (!subscriber.isShutdown()) {
				subscriber.schedule(() -> subscribe(container, Math.min(retrySeconds * 2, MAX_RETRY_SECONDS)),
						retrySeconds, TimeUnit.SECONDS);
			}
		}
	}

	@PreDestroy
	void stopRetrying() {
		subscriber.shutdownNow();
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
