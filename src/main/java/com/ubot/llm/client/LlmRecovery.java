package com.ubot.llm.client;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

// Worker 계산 스레드에만 설치함. 실행 마감은 Worker가 계산 Future를 취소(인터럽트)해서 끝내므로 여기서는 따로 보지 않음
@Slf4j
public final class LlmRecovery {

	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
	private LlmRecovery() {}

	public static <T> T observe(Duration backoff, Runnable checkActive, Runnable onWait, Supplier<T> computation) {
		Context previous = CURRENT.get();
		CURRENT.set(new Context(backoff, checkActive, onWait));
		try {
			return computation.get();
		} finally {
			if (previous == null) CURRENT.remove();
			else CURRENT.set(previous);
		}
	}

	static <T> T call(Supplier<T> request) {
		Context context = CURRENT.get();
		if (context == null) return request.get();
		int retries = 0;
		long startedAt = System.nanoTime();
		while (true) {
			context.checkActive().run();
			try {
				T result = request.get();
				if (retries > 0) {
					log.info("모델 연결 복구 후 호출이 성공했습니다: 재시도횟수={}, 소요시간={}ms",
							retries, (System.nanoTime() - startedAt) / 1_000_000);
				}
				return result;
			} catch (LlmException exception) {
				// 재시도하지 않는 오류는 ChatJobProcessor가 최종 오류 코드로 기록함
				if (!recoverable(exception)) throw exception;
				// 취소된 작업이면 대기 단계를 알리지 않고 바로 끝냄
				context.checkActive().run();
				context.onWait().run();
				retries++;
				log.warn("모델 연결 실패로 복구를 기다립니다: 오류코드={}, 원인예외={}, HTTP상태={}, 재시도횟수={}, 대기시간={}ms",
						exception.getErrorCode().getCode(), causeType(exception), httpStatus(exception),
						retries, context.backoff().toMillis());
				try {
					Thread.sleep(context.backoff());
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new CancellationException("모델 복구 대기를 중단했습니다.");
				}
			}
		}
	}

	private static String causeType(LlmException exception) {
		return exception.getCause() == null ? exception.getClass().getSimpleName() : exception.getCause().getClass().getSimpleName();
	}

	private static Integer httpStatus(LlmException exception) {
		return exception.getCause() instanceof RestClientResponseException response ? response.getStatusCode().value() : null;
	}

	// 연결 실패(접속 거부·응답 중 끊김)와 5xx만 일시 장애로 봄. 4xx·알 수 없는 응답 형식·코드 오류는 바로 실패
	private static boolean recoverable(LlmException exception) {
		if (exception.getErrorCode() != LlmErrorCode.LLM_SERVICE_UNAVAILABLE) return false;
		Throwable cause = exception.getCause();
		return cause instanceof ResourceAccessException || cause instanceof HttpServerErrorException;
	}

	private record Context(Duration backoff, Runnable checkActive, Runnable onWait) {}
}
