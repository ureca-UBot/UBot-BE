package com.ubot.chat.entity;

import com.ubot.chat.entity.converter.AnswerAttemptErrorCodeConverter;
import com.ubot.common.ErrorCode;
import com.ubot.faq.enums.Intent;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 행은 한 번의 답변 생성 시도이며, 재시도는 최초 발급된 멱등키를 공유합니다.
 * sourceAttemptId/intent가 채워진 행은 "성공한 원본 attempt에 대한, 특정 intent로의 재검색" 시도입니다.
 */
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

	@Column(name = "conversation_id")
	private Long conversationId;

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
	@Convert(converter = AnswerAttemptErrorCodeConverter.class)
	private ErrorCode errorCode;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "source_attempt_id")
	private Long sourceAttemptId;

	@Enumerated(EnumType.STRING)
	@Column(name = "intent")
	private Intent intent;

	public AnswerAttemptsHistory(
			Long userId,
			String question,
			int attemptCount,
			String idempotencyKey,
			LocalDateTime createdAt,
			String llmModel,
			String embeddingModel) {
		this(userId, null, question, attemptCount, idempotencyKey, createdAt, llmModel, embeddingModel);
	}

	public AnswerAttemptsHistory(
			Long userId,
			Long conversationId,
			String question,
			int attemptCount,
			String idempotencyKey,
			LocalDateTime createdAt,
			String llmModel,
			String embeddingModel) {
		this.userId = userId;
		this.conversationId = conversationId;
		this.question = question;
		this.attemptCount = attemptCount;
		this.idempotencyKey = idempotencyKey;
		this.createdAt = createdAt;
		this.llmModel = llmModel;
		this.embeddingModel = embeddingModel;
		this.status = "PENDING";
	}

	/**
	 * 성공한 원본을 사용자가 고른 intent로 다시 검색하는 새 attempt입니다. 재시도 체인과 분리하려고 호출하는 쪽에서 새 멱등키를
	 * 넘깁니다. 질문과 소유자·대화는 원본에서 이어받고, 모델은 이번에 사용하는 설정값을 기록합니다(재시도와 같습니다).
	 */
	public static AnswerAttemptsHistory createResearchAttempt(
			AnswerAttemptsHistory source,
			Intent intent,
			String idempotencyKey,
			LocalDateTime createdAt,
			String llmModel,
			String embeddingModel) {
		Objects.requireNonNull(source, "source는 필수입니다.");
		Objects.requireNonNull(source.id, "저장된 원본 attempt가 필요합니다.");
		Objects.requireNonNull(source.userId, "재검색은 회원 attempt만 가능합니다.");
		Objects.requireNonNull(intent, "intent는 필수입니다.");
		return AnswerAttemptsHistory.builder()
				.userId(source.userId)
				.conversationId(source.conversationId)
				.question(source.question)
				.attemptCount(1)
				.idempotencyKey(idempotencyKey)
				.sourceAttemptId(source.id)
				.intent(intent)
				.createdAt(createdAt)
				.llmModel(llmModel)
				.embeddingModel(embeddingModel)
				.status("PENDING")
				.build();
	}

	/** 의도 재검색으로 만들어진 attempt인지 반환합니다. */
	public boolean isResearch() {
		return sourceAttemptId != null;
	}

	public void succeed() {
		this.status = "SUCCESS";
	}

	public void fail(ErrorCode errorCode) {
		this.status = "FAIL";
		this.errorCode = errorCode;
		this.errorMessage = errorCode.getMessage();
	}
}