package com.ubot.chat.service;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.service.TelemapOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class QuestionLogService {
	private final QuestionLogRepository questionLogRepository;
	private final IpRegionResolver ipRegionResolver;
	private final TelemapOutboxService telemapOutboxService;

	@Value("${ranking.window-minutes}")
	private int rankingWindowMinutes;

	@Transactional
	List<QuestionLog> saveTurn(
			Long userId,
			Long conversationId,
			Long attemptId,
			String question,
			List<SubQuestionFaqs> subQuestions,
			String answer,
			String userIp
	){
		return saveTurn(userId, conversationId, attemptId, question, subQuestions, answer,
				ipRegionResolver.resolve(userIp).orElse(null));
	}

	/**
	 * 한 턴을 하위 질문마다 질문 로그 한 행으로 저장하고, 하위 질문 순서대로 반환합니다.
	 * question은 사용자가 입력한 원문으로 모든 행에 같은 값이 들어가고, 랭킹 중복 판정도 이 원문으로 합니다.
	 * 하위 질문마다 검색어는 sub_question, 원문 조각은 user_text 컬럼에 저장합니다. 질문을 나누지 않았으면 원문 하나를 넘깁니다.
	 * Worker는 요청 IP 대신 접수 때 정한 지역을 넘깁니다.
	 */
	@Transactional
	List<QuestionLog> saveTurn(
			Long userId,
			Long conversationId,
			Long attemptId,
			String question,
			List<SubQuestionFaqs> subQuestions,
			String answer,
			RegionSido regionSido
	){

		String normalizedQuestion = normalize(question);

		// 게스트 질문은 세션을 새로 만들기 쉬워 랭킹 조작 비용이 낮으므로 실시간 검색어 집계에서 제외합니다.
		// 로그인 이후 생성된 회원 질문부터 집계에 포함합니다.
		boolean rankingEligible = userId != null
				&& judgeRankingEligible(userId, normalizedQuestion);

		List<QuestionLog> questionLogs = new ArrayList<>();
		for (int index = 0; index < subQuestions.size(); index++) {
			questionLogs.add(QuestionLog.builder()
					.userId(userId)
					.conversationId(conversationId)
					.attemptId(attemptId)
					.userQuestion(question)
					.subQuestion(subQuestions.get(index).searchQuery())
					.userText(subQuestions.get(index).userText())
					.subQuestionIndex(index + 1)
					.normalizedQuestion(normalizedQuestion)
					.answer(answer)
					.rankingEligible(rankingEligible)
					.region(regionSido)
					.build());
		}
		List<QuestionLog> savedQuestionLogs = questionLogRepository.saveAllAndFlush(questionLogs);
		telemapOutboxService.enqueueSurgingQuestions(savedQuestionLogs);
		return savedQuestionLogs;
	}

	/**
	 * 랭킹 시간 창 안에 같은 사용자의 같은 원문 질문이 없을 때만 해당 턴 전체를 랭킹 대상으로 봅니다.
	 */
	private boolean judgeRankingEligible(Long userId, String normalizedQuestion) {
		// 같은 사용자의 같은 원문 질문이 동시에 저장되어 중복 집계되지 않도록 잠급니다.
		questionLogRepository.acquireAdvisoryLock(
				(long) (userId + ":" + normalizedQuestion).hashCode());

		LocalDateTime endAt = LocalDateTime.now();
		LocalDateTime startAt = endAt.minusMinutes(rankingWindowMinutes);
		return !questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
				userId, normalizedQuestion, startAt, endAt);
	}

	private String normalize(String question) {
		return question
				.strip()
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.replaceAll("[?!.,~]+", "")
				.replaceAll("\\s+", " ")
				.strip();
	}
}
