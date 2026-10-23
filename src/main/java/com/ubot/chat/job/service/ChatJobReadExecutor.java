package com.ubot.chat.job.service;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

// 작업 조회의 인증·DB 접근만 제한함. 접수·Worker의 DB 대기 설정은 유지함
@Component
@Slf4j
public class ChatJobReadExecutor {

	private static final String DEADLINE = ChatJobReadExecutor.class.getName() + ".deadline";
	private final Duration budget;
	// 취소에 즉시 반응하지 않는 JDBC 호출도 무한히 쌓이지 않게 함
	private final ThreadPoolExecutor executor;

	public ChatJobReadExecutor(
			@Value("${app.chat.job-read.budget:1500ms}") Duration budget,
			@Value("${app.chat.job-read.max-concurrency:8}") int maxConcurrency) {
		if (budget.isNegative() || budget.isZero()) {
			throw new IllegalArgumentException("app.chat.job-read.budget는 0보다 커야 합니다: " + budget);
		}
		this.budget = budget;
		this.executor = new ThreadPoolExecutor(0, maxConcurrency, 30, TimeUnit.SECONDS,
				new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("chat-job-read-", 0).factory());
	}

	// 작업 조회 요청에서만 호출함. 같은 요청의 호출은 한 예산(app.chat.job-read.budget)을 나눠 씀
	public <T> T execute(HttpServletRequest request, Supplier<T> read) {
		Long deadline = (Long) request.getAttribute(DEADLINE);
		if (deadline == null) {
			deadline = System.nanoTime() + budget.toNanos();
			request.setAttribute(DEADLINE, deadline);
		}
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			throw unavailable("deadline");
		}
		Future<T> task;
		try {
			task = executor.submit(new DelegatingSecurityContextCallable<>(read::get));
		} catch (RejectedExecutionException exception) {
			throw unavailable("capacity");
		}
		try {
			return task.get(remaining, TimeUnit.NANOSECONDS);
		} catch (TimeoutException exception) {
			task.cancel(true);
			throw unavailable("deadline");
		} catch (InterruptedException exception) {
			task.cancel(true);
			Thread.currentThread().interrupt();
			throw unavailable("interrupted");
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof DataAccessException || cause instanceof TransactionException) {
				throw unavailable(cause.getClass().getSimpleName());
			}
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(cause);
		}
	}

	private ChatException unavailable(String reason) {
		// 응답 오류는 GlobalExceptionHandler가 WARN으로 기록함. 여기서는 진단 정보만 보완함
		log.debug("답변 작업 조회 실패 상세: 오류코드={}, 원인={}, 실행중조회수={}",
				ChatErrorCode.JOB_QUERY_UNAVAILABLE.getCode(), reason, executor.getActiveCount());
		return new ChatException(ChatErrorCode.JOB_QUERY_UNAVAILABLE);
	}

	@PreDestroy
	public void close() {
		executor.shutdownNow();
	}
}
