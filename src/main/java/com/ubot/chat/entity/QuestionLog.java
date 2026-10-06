package com.ubot.chat.entity;

import com.ubot.ranking.enums.RegionSido;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "question_log")
public class QuestionLog {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "conversation_id")
	private Long conversationId;

	@Column(name = "user_question")
	private String userQuestion;

	@Column(name = "normalized_question")
	private String normalizedQuestion;

	// 팀 ERD의 기존 컬럼명이며, 내용은 LLM이 생성한 답변입니다.
	@Column(name = "llm_question")
	private String answer;

	@Column(name = "ranking_eligible")
	private Boolean rankingEligible;

	@Enumerated(EnumType.STRING)
	@Column(name = "region")
	private RegionSido region;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Builder
	private QuestionLog(
			Long userId,
			Long conversationId,
			String userQuestion,
			String normalizedQuestion,
			String answer,
			Boolean rankingEligible,
			RegionSido region
	){
		this.userId = userId;
		this.conversationId = conversationId;
		this.userQuestion = userQuestion;
		this.normalizedQuestion = normalizedQuestion;
		this.answer = answer;
		this.rankingEligible = rankingEligible;
		this.region = region;
		this.createdAt = LocalDateTime.now();
	}
}
