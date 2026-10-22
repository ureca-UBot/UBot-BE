package com.ubot.chat.job.stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.chat.job.worker.ChatJobWorker;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

class ChatJobCancellationPublisherTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final DefaultListableBeanFactory localBeans = new DefaultListableBeanFactory();

	@Test
	void apiWithoutLocalWorkerPublishesOnlyTheSelectedJobId() {
		publisher().publish(42L);
		verify(redis).convertAndSend(ChatJobCancellationPublisher.CHANNEL, "42");
	}

	@Test
	void sameProcessWorkerIsCancelledBeforePublishing() {
		var worker = mock(ChatJobWorker.class);
		localBeans.registerSingleton("worker", worker);
		doAnswer(call -> {
			verify(worker).cancelJob(42L);
			return 1L;
		}).when(redis).convertAndSend(ChatJobCancellationPublisher.CHANNEL, "42");

		publisher().publish(42L);
		verify(redis).convertAndSend(ChatJobCancellationPublisher.CHANNEL, "42");
	}

	@Test
	void separateApiPublisherDeliversTheIdToWorkerSubscriber() {
		var remoteWorker = mock(ChatJobWorker.class);
		var subscriber = new ChatJobCancellationSubscriber(remoteWorker);
		doAnswer(call -> {
			var message = mock(Message.class);
			when(message.getBody()).thenReturn(call.<String>getArgument(1).getBytes(StandardCharsets.UTF_8));
			subscriber.onMessage(message, null);
			return 1L;
		}).when(redis).convertAndSend(ChatJobCancellationPublisher.CHANNEL, "42");

		publisher().publish(42L);
		verify(remoteWorker).cancelJob(42L);
	}

	@Test
	void redisFailureKeepsCommittedCancellationAndLocalInterruption() {
		var worker = mock(ChatJobWorker.class);
		localBeans.registerSingleton("worker", worker);
		doThrow(new DataAccessResourceFailureException("redis unavailable"))
				.when(redis).convertAndSend(ChatJobCancellationPublisher.CHANNEL, "42");

		assertThatCode(() -> publisher().publish(42L)).doesNotThrowAnyException();
		verify(worker).cancelJob(42L);
	}

	private ChatJobCancellationPublisher publisher() {
		return new ChatJobCancellationPublisher(redis, localBeans.getBeanProvider(ChatJobWorker.class));
	}
}
