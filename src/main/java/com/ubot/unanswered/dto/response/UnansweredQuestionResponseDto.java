package com.ubot.unanswered.dto.response;

import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;

import java.time.LocalDateTime;

/**
 * question은 사용자가 입력한 원문 조각이고, searchQuery는 판정 LLM이 맥락을 채워 다시 쓴 검색어입니다.
 * 원문 조각만으로는 "거기 주차 돼요?"처럼 무엇을 물었는지 알 수 없을 수 있어 검색어를 함께 보여 줍니다.
 * 질문을 나누거나 다시 쓰지 않았으면 두 값이 같습니다.
 */
public record UnansweredQuestionResponseDto(
		Long id,
		String question,
		String searchQuery,
		UnansweredReason reason,
		UnansweredSource source,
		Long bestFaqId,
		Double bestSimilarity,
		LocalDateTime createdAt
) {
	public static UnansweredQuestionResponseDto from(UnansweredQuestion question){
		return new UnansweredQuestionResponseDto(
				question.getId(),
				question.getUserText(),
				question.getSubQuestion(),
				question.getReason(),
				question.getSource(),
				question.getBestFaqId(),
				question.getBestSimilarity(),
				question.getCreatedAt()
		);
	}
}
