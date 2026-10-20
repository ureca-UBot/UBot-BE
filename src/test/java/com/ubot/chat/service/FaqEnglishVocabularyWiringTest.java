package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.service.FaqChangedEvent;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/** 최소 Spring 컨텍스트와 임시 Redis만 사용합니다. Kiwi·LLM·PostgreSQL은 띄우지 않습니다. */
class FaqEnglishVocabularyWiringTest {
	@Test
	void initializesAndRefreshesOnlyCommittedChangesThroughActualRedisSubscription() throws Exception {
		var repository = mock(FaqRepository.class);
		when(repository.findActiveEnglishWords(null)).thenReturn(List.of(word(1L, "esim")));
		when(repository.findActiveEnglishWords(2L)).thenReturn(List.of(word(2L, "faq")));
		try (var redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
				var context = new AnnotationConfigApplicationContext()) {
			redis.start();
			context.registerBean(FaqRepository.class, () -> repository);
			context.registerBean(RedisConnectionFactory.class,
					() -> new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379)));
			context.registerBean(StringRedisTemplate.class,
					() -> spy(new StringRedisTemplate(context.getBean(RedisConnectionFactory.class))));
			context.registerBean(TestTransactionManager.class);
			context.register(TransactionConfig.class, SubQuestionEnglishPolicy.class, FaqEnglishVocabularyEvents.class);
			context.refresh();
			var policy = context.getBean(SubQuestionEnglishPolicy.class);
			// 목록은 기동 시 바로 읽고, 구독은 별도 스레드에서 연결되므로 구독될 때까지 기다린 뒤 알림을 보냅니다.
			assertThat(policy.requiresSource("eSIM")).isFalse();
			var container = context.getBean(RedisMessageListenerContainer.class);
			await(container::isListening);
			assertThat(policy.requiresSource("FAQ")).isTrue();
			var transactions = new TransactionTemplate(context.getBean(TestTransactionManager.class));
			var change = new FaqChangedEvent(Set.of(2L));

			transactions.executeWithoutResult(status -> {
				context.publishEvent(change);
				assertThat(policy.requiresSource("FAQ")).as("커밋 전에는 갱신하지 않음").isTrue();
			});
			await(() -> !policy.requiresSource("FAQ"));
			verify(repository).findActiveEnglishWords(2L);

			when(repository.findActiveEnglishWords(2L)).thenReturn(List.of());
			transactions.executeWithoutResult(status -> {
				context.publishEvent(change);
				status.setRollbackOnly();
			});
			verify(context.getBean(StringRedisTemplate.class), times(1))
					.convertAndSend(FaqEnglishVocabularyEvents.CHANNEL, "2");
			assertThat(policy.requiresSource("FAQ")).as("롤백한 변경은 반영하지 않음").isFalse();

			transactions.executeWithoutResult(status -> context.publishEvent(change));
			await(() -> policy.requiresSource("FAQ"));
			// 기동 시 한 번, 첫 구독 연결 때 한 번 전체 목록을 읽습니다.
			verify(repository, times(2)).findActiveEnglishWords(null);
			verify(repository, times(2)).findActiveEnglishWords(2L);
		}
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private static FaqRepository.EnglishWord word(long faqId, String value) {
		return new FaqRepository.EnglishWord() {
			public Long getFaqId() { return faqId; }
			public String getWord() { return value; }
		};
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class TransactionConfig {
	}

	static class TestTransactionManager extends AbstractPlatformTransactionManager {
		protected Object doGetTransaction() { return new Object(); }
		protected void doBegin(Object transaction, TransactionDefinition definition) { }
		protected void doCommit(DefaultTransactionStatus status) { }
		protected void doRollback(DefaultTransactionStatus status) { }
	}
}
