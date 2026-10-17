package com.ubot.chat.job.entity;

// SUCCEEDED·FAILED는 다시 바뀌지 않음
public enum ChatJobStatus {
	QUEUED,
	RUNNING,
	SUCCEEDED,
	FAILED;

	public boolean isFinished() {
		return this == SUCCEEDED || this == FAILED;
	}
}
