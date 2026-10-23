package com.ubot.guardreview.service;

import com.ubot.global.config.AppRole;
import com.ubot.guardreview.exception.ReviewLeaseLostException;
import com.ubot.guardreview.repository.ReviewRepository;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/** 검증 계산을 실행하는 스레드와 분리하여 기존 DB lease를 갱신합니다. */
@Service
@Slf4j
@ConditionalOnExpression(AppRole.RUNS_WORKER)
public class ReviewLeaseService {
	private final ReviewRepository repository;
	private final ScheduledExecutorService scheduler;
	private volatile boolean stopped;
	@Autowired
	public ReviewLeaseService(ReviewRepository repository) {
		this(repository, Executors
				.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("guard-review-lease").daemon().factory()));
	}
	ReviewLeaseService(ReviewRepository repository, ScheduledExecutorService scheduler) {
		this.repository = repository;
		this.scheduler = scheduler;
	}
	public interface Lease extends AutoCloseable {
		void check();
		@Override
		void close();
	}
	public Lease maintain(long id, UUID token) {
		if (stopped)
			throw new ReviewLeaseLostException();
		var lease = new ActiveLease(id, token);
		lease.future = scheduler.scheduleWithFixedDelay(lease::renew, 30, 30, TimeUnit.SECONDS);
		return lease;
	}
	@PreDestroy
	public void close() {
		stopped = true;
		scheduler.shutdownNow();
	}
	private final class ActiveLease implements Lease {
		private final long id;
		private final UUID token;
		private final AtomicBoolean closed = new AtomicBoolean();
		private volatile boolean owned = true;
		private ScheduledFuture<?> future;
		private ActiveLease(long id, UUID token) {
			this.id = id;
			this.token = token;
		}
		void renew() {
			if (closed.get() || !owned)
				return;
			try {
				owned = repository.renewLease(id, token);
			} catch (RuntimeException e) {
				owned = false;
				log.warn("관리자 검토 lease 갱신 실패: 실행ID={}, 오류유형={}", id, e.getClass().getSimpleName());
			}
		}
		@Override
		public void check() {
			if (!owned || stopped || closed.get() || Thread.currentThread().isInterrupted())
				throw new ReviewLeaseLostException();
		}
		@Override
		public void close() {
			closed.set(true);
			future.cancel(false);
		}
	}
}
