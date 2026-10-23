package com.ubot.guardreview.service;

/** 한 분석 배치가 끝날 때까지 관리자 전용 모델의 사용권을 유지합니다. */
public interface ReviewModelRuntime {
	Session acquire(long runId, Runnable renewWorkLease);

	interface Session extends AutoCloseable {
		void ensureValid();
		@Override
		void close();
	}

	class Busy extends RuntimeException {
		public Busy() {
			super("관리자 분석 모델을 올릴 GPU 여유가 없어 작업을 대기합니다.");
		}
	}
}
