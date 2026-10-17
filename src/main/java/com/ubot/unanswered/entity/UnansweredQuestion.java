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

	// 실패한 하위 질문의 검색어(판정 LLM이 다시 쓴 것)입니다. 중복 판정·묶음(임베딩)·묶음 대표 질문에 쓰고 관리자 화면에도 함께 보여 줍니다.
	// 질문을 나누지 않았으면 사용자가 입력한 원문과 같습니다.
	@Column(name = "sub_question")
	private String subQuestion;

	// 관리자 화면에 검색어와 함께 보여 줄 원문 조각입니다. 원문을 자를 근거가 없었으면 원문 전체입니다.
	@Column(name = "user_text")
	private String userText;

	@Column(name = "sub_question_index")
	@Builder.Default
	private int subQuestionIndex = 1;

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
