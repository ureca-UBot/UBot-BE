package com.ubot.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 한 행은 한 번의 답변 생성 시도이며, 재시도는 최초 발급된 멱등키를 공유합니다. */
@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Table(name = "answer_attempts_history")
public class AnswerAttemptsHistory {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "attempt_id")
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "question")
	private String question;

	@Column(name = "attempt_count")
	private int attemptCount;

	@Column(name = "status")
	private String status;

	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(name = "llm_model")
	private String llmModel;

	@Column(name = "embedding_model")
	private String embeddingModel;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	public AnswerAttemptsHistory(
			Long userId,
			String question,
			int attemptCount,
			String idempotencyKey,
			LocalDateTime createdAt,
			String llmModel,
			String embeddingModel
	) {
		this.userId = userId;
		this.question = question;
		this.attemptCount = attemptCount;
		this.idempotencyKey = idempotencyKey;
		this.createdAt = createdAt;
		this.llmModel = llmModel;
		this.embeddingModel = embeddingModel;
		this.status = "PENDING";
	}

	public void succeed() {
		this.status = "SUCCESS";
	}

	public void fail(String code, String message) {
		this.status = "FAIL";
		this.errorCode = code;
		this.errorMessage = message;
	}
}
