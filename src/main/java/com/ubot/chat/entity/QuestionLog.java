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

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Table(name = "question_log")
public class QuestionLog {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "user_question")
	private String userQuestion;

	// 팀 ERD의 기존 컬럼명이며, 내용은 LLM이 생성한 답변입니다.
	@Column(name = "llm_question")
	private String answer;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	public QuestionLog(Long userId, String question, String answer, LocalDateTime createdAt) {
		this.userId = userId;
		this.userQuestion = question;
		this.answer = answer;
		this.createdAt = createdAt;
	}
}
