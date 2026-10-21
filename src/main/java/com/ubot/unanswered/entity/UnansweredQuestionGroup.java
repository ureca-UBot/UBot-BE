package com.ubot.unanswered.entity;

import com.ubot.unanswered.enums.UnansweredGroupStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
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

	@Column(name = "resolved_faq_id")
	private Long resolvedFaqId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private UnansweredGroupStatus status;

	@Column(name = "last_occurred_at")
	private LocalDateTime lastOccurredAt;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@Column(name = "topic_name", insertable = false, updatable = false)
	private String topicName;

	@Formula("(SELECT c.id FROM faq_category c WHERE c.id = suggested_category_id AND c.deleted_at IS NULL)")
	private Long suggestedCategoryId;

	public void updateStatus(UnansweredGroupStatus status){
		this.status = status;
		this.updatedAt = LocalDateTime.now();
	}

	public void resolve(Long faqId){
		this.resolvedFaqId = faqId;
		this.status = UnansweredGroupStatus.APPROVED;
		this.updatedAt = LocalDateTime.now();
	}
}
