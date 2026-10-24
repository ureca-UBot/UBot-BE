package com.ubot.chat.job.stream;

import com.ubot.chat.job.worker.ChatJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 취소 커밋 후 호출합니다. API 전용 프로세스에는 Worker가 없어도 됩니다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatJobCancellationPublisher {

	static final String CHANNEL = "ubot:chat:job:cancel";
	private final StringRedisTemplate redis;
	private final ObjectProvider<ChatJobRunner> localRunners;

	public void publish(long jobId) {
		localRunners.ifAvailable(runner -> runner.cancelJob(jobId));
		try {
			redis.convertAndSend(CHANNEL, Long.toString(jobId));
		} catch (DataAccessException exception) {
			// 취소 상태는 이미 DB에 확정됐습니다. 알림 실패 시 기존 heartbeat 확인이 대신 중단합니다.
			log.warn("답변 작업 취소 알림을 전송하지 못했습니다. Worker의 상태 확인으로 중단합니다: 작업ID={}",
					jobId, exception);
		}
	}
}
