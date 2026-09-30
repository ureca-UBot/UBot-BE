package com.ubot.unanswered.entity;

import com.ubot.unanswered.enums.UnansweredGroupStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Table(name = "unanswered_question_groups")
public class UnansweredQuestionGroup {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "representative_question")
	private String representativeQuestion;

	@Column(name = "question_count")
	private Integer questionCount;

	@Column(name = "related_faq_id")
	private Long relatedFaqId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private UnansweredGroupStatus status;

	@Column(name = "last_occurred_at")
	private LocalDateTime lastOccurredAt;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	public void updateStatus(UnansweredGroupStatus status){
		this.status = status;
		this.updatedAt = LocalDateTime.now();
	}
}
