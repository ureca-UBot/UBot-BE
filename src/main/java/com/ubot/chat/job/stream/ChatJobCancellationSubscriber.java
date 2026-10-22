package com.ubot.chat.job.stream;

import com.ubot.chat.job.worker.ChatJobWorker;
import com.ubot.global.config.AppRole;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;

/** 각 Worker 프로세스가 작업 ID만 받아 자신의 실행 중인 작업에 중단을 전달합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(AppRole.RUNS_WORKER)
@RequiredArgsConstructor
@Slf4j
public class ChatJobCancellationSubscriber implements MessageListener {

	private final ChatJobWorker worker;
	private RedisMessageListenerContainer listener;

	@Bean
	RedisMessageListenerContainer chatJobCancellationListener(RedisConnectionFactory connectionFactory) {
		listener = new RedisMessageListenerContainer();
		listener.setConnectionFactory(connectionFactory);
		listener.addMessageListener(this, new ChannelTopic(ChatJobCancellationPublisher.CHANNEL));
		// 부가 알림의 구독 실패가 서버 기동을 막지 않도록 기동 후 재시도합니다.
		listener.setAutoStartup(false);
		return listener;
	}

	@Scheduled(fixedDelayString = "${app.chat.worker.recovery-interval:15s}")
	void subscribe() {
		if (listener == null || listener.isRunning()) return;
		try {
			listener.start();
		} catch (RuntimeException exception) {
			// 실패한 첫 구독의 상태를 지워 다음 주기에 다시 구독할 수 있게 합니다.
			listener.stop();
			log.warn("답변 작업 취소 알림을 구독하지 못해 다시 시도합니다. 그동안은 heartbeat로 확인합니다", exception);
		}
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		long jobId;
		try {
			jobId = Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8));
			if (jobId < 1) throw new IllegalArgumentException("작업 ID는 양수여야 합니다.");
		} catch (IllegalArgumentException exception) {
			log.warn("답변 작업 취소 알림의 작업 ID가 올바르지 않습니다");
			return;
		}
		worker.cancelJob(jobId);
	}
}
