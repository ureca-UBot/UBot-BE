package com.ubot.chat.job.worker;

import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

final class RunningJob {
	final ReceivedChatJob received;
	final LocalDateTime startedAt;
	volatile boolean lost;
	volatile Future<ChatJobOutcome> computation;
	final List<UnansweredCandidate> unanswered = new CopyOnWriteArrayList<>();

	void bindComputation(Future<ChatJobOutcome> future) {
		computation = future;
		// cancel()이 Future 연결 전에 실행된 경우에도 중단 신호를 잃지 않습니다.
		if (lost) future.cancel(true);
	}

	void cancel() {
		lost = true;
		Future<?> current = computation;
		if (current != null) current.cancel(true);
	}

	RunningJob(ReceivedChatJob received, LocalDateTime startedAt) {
		this.received = received;
		this.startedAt = startedAt;
	}

	void checkActive() {
		if (lost || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("답변 작업을 계속할 수 없습니다: 작업ID=" + received.message().jobId());
		}
	}
}
