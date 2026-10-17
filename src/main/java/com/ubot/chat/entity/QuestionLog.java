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

	// 한 행은 하위 질문 하나입니다. 같은 답변 시도(턴)의 행은 attemptId로 묶입니다.
	@Column(name = "attempt_id")
	private Long attemptId;

	// 사용자가 입력한 원문입니다. 같은 턴의 모든 행에 같은 값이 들어가며 대화 기록 화면에 씁니다.
	@Column(name = "user_question")
	private String userQuestion;

	// 검색·랭킹에 쓴 하위 질문의 검색어(판정 LLM이 다시 쓴 것)입니다. 하위 질문 저장 이전의 행은 null입니다.
	@Column(name = "sub_question")
	private String subQuestion;

	// 하위 질문에 해당하는 원문 조각입니다. 질문을 나눴지만 원문을 자를 근거가 없었던 턴과 하위 질문 저장 이전의 행은 null입니다.
	@Column(name = "user_text")
	private String userText;

	@Column(name = "sub_question_index")
	private int subQuestionIndex;

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
			Long attemptId,
			String userQuestion,
			String subQuestion,
			String userText,
			int subQuestionIndex,
			String normalizedQuestion,
			String answer,
			Boolean rankingEligible,
			RegionSido region
	){
		this.userId = userId;
		this.conversationId = conversationId;
		this.attemptId = attemptId;
		this.userQuestion = userQuestion;
		this.subQuestion = subQuestion;
		this.userText = userText;
		this.subQuestionIndex = subQuestionIndex;
		this.normalizedQuestion = normalizedQuestion;
		this.answer = answer;
		this.rankingEligible = rankingEligible;
		this.region = region;
		this.createdAt = LocalDateTime.now();
	}
}
