package com.ubot.chat.job.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCancellationService;
import com.ubot.chat.job.service.ChatJobQueryService;
import com.ubot.chat.job.worker.ChatJobRunner;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class ChatJobCancellationSubscriberTest {

	private final ChatJobRunner runner = mock(ChatJobRunner.class);
	private final ChatJobCancellationSubscriber subscriber = new ChatJobCancellationSubscriber(runner);

	@Test
	void apiRoleStartsCancellationServiceWithoutWorkerOrSubscription() {
		context("api").run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatJobCancellationService.class)
					.hasSingleBean(ChatJobCancellationPublisher.class).doesNotHaveBean(ChatJobRunner.class)
					.doesNotHaveBean(ChatJobCancellationSubscriber.class)
					.doesNotHaveBean("chatJobCancellationListener");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"worker", "all"})
	void workerRolesHaveSubscriberWithoutBlockingStartupOnRedis(String role) {
		context(role).withBean(ChatJobRunner.class, () -> runner).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ChatJobCancellationSubscriber.class)
					.hasBean("chatJobCancellationListener");
			assertThat(context.getBean("chatJobCancellationListener", RedisMessageListenerContainer.class)
					.isAutoStartup()).isFalse();
			verify(context.getBean(RedisConnectionFactory.class), never()).getConnection();
			verify(runner, never()).cancelJob(anyLong());
		});
	}

	@Test
	void receivedIdOnlyCancelsThatJob() {
		subscriber.onMessage(message("42"), null);
		verify(runner).cancelJob(42L);
		verify(runner, times(1)).cancelJob(anyLong());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "0", "-1", "bad", "1,2", "9223372036854775808"})
	void invalidMessagesDoNotCancelAnyJob(String body) {
		subscriber.onMessage(message(body), null);
		verifyNoInteractions(runner);
	}

	@Test
	void failedSubscriptionCanBeRetriedWithoutChangingAnyJob() {
		var listener = mock(RedisMessageListenerContainer.class);
		ReflectionTestUtils.setField(subscriber, "listener", listener);
		when(listener.isRunning()).thenReturn(false, false, true);
		doThrow(new IllegalStateException("redis unavailable")).doNothing().when(listener).start();

		subscriber.subscribe();
		subscriber.subscribe();
		subscriber.subscribe();

		verify(listener, times(2)).start();
		verify(listener).stop();
		verifyNoInteractions(runner);
	}

	private ApplicationContextRunner context(String role) {
		return new ApplicationContextRunner().withPropertyValues("app.role=" + role)
				.withUserConfiguration(ChatJobCancellationService.class, ChatJobCancellationPublisher.class,
						ChatJobCancellationSubscriber.class)
				.withBean(ChatJobQueryService.class, () -> mock(ChatJobQueryService.class))
				.withBean(ChatJobRepository.class, () -> mock(ChatJobRepository.class))
				.withBean(AnswerAttemptsHistoryRepository.class, () -> mock(AnswerAttemptsHistoryRepository.class))
				.withBean(ChatAttemptsService.class, () -> mock(ChatAttemptsService.class))
				.withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
				.withBean(EntityManager.class, () -> mock(EntityManager.class))
				.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
				.withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));
	}

	private Message message(String body) {
		var message = mock(Message.class);
		when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
		return message;
	}
}
