package com.ubot.unanswered.entity;

import com.ubot.unanswered.enums.UnansweredReason;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Table(name = "unanswered_questions")
public class UnansweredQuestion {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "attempt_id")
	private Long attemptId;

	@Column(name = "group_id")
	private Long groupId;

	// 실패한 하위 질문입니다. 질문을 나누지 않았으면 사용자가 입력한 원문과 같습니다.
	@Column(name = "sub_question")
	private String subQuestion;

	@Column(name = "sub_question_index")
	private int subQuestionIndex;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason")
	private UnansweredReason reason;

	@Column(name = "best_faq_id")
	private Long bestFaqId;

	@Column(name = "best_similarity")
	private Double bestSimilarity;

	@Column(name = "created_at")
	private LocalDateTime createdAt;
}
