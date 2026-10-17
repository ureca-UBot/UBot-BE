package com.ubot.chat.service;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QuestionLogService {
	private final QuestionLogRepository questionLogRepository;
	private final IpRegionResolver ipRegionResolver;

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
	 * question은 사용자가 입력한 원문으로 모든 행에 같은 값이 들어가고, 랭킹 중복 판정은 검색어로 합니다.
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

		List<String> normalizedQuestions = subQuestions.stream()
				.map(subQuestion -> normalize(subQuestion.searchQuery()))
				.toList();

		// 게스트 질문은 세션을 새로 만들기 쉬워 랭킹 조작 비용이 낮으므로 실시간 검색어 집계에서 제외합니다.
		// 로그인 이후 생성된 회원 질문부터 집계에 포함합니다.
		List<Boolean> rankingEligible = userId == null
				? normalizedQuestions.stream().map(normalized -> false).toList()
				: judgeRankingEligible(userId, normalizedQuestions);

		List<QuestionLog> questionLogs = new ArrayList<>();
		for (int index = 0; index < subQuestions.size(); index++) {
			questionLogs.add(QuestionLog.builder()
					.userId(userId)
					.conversationId(conversationId)
					.attemptId(attemptId)
					.userQuestion(question)
					.subQuestion(subQuestions.get(index).searchQuery())
					.userText(subQuestions.get(index).userText())
					.subQuestionIndex(index)
					.normalizedQuestion(normalizedQuestions.get(index))
					.answer(answer)
					.rankingEligible(rankingEligible.get(index))
					.region(regionSido)
					.build());
		}
		return questionLogRepository.saveAllAndFlush(questionLogs);
	}

	/**
	 * 하위 질문마다 랭킹 시간 창 안에 같은 사용자의 같은 질문이 없을 때만 랭킹 대상으로 봅니다.
	 * 같은 턴 안에서 같은 질문이 두 번 나오면 처음 것만 랭킹 대상입니다.
	 */
	private List<Boolean> judgeRankingEligible(Long userId, List<String> normalizedQuestions) {
		// 하위 질문마다 잠금을 잡으므로, 두 요청이 서로 반대 순서로 잡아 교착되지 않도록 항상 같은 순서로 잡습니다.
		normalizedQuestions.stream()
				.map(normalized -> (long) (userId + ":" + normalized).hashCode())
				.distinct()
				.sorted()
				.forEach(questionLogRepository::acquireAdvisoryLock);

		LocalDateTime endAt = LocalDateTime.now();
		LocalDateTime startAt = endAt.minusMinutes(rankingWindowMinutes);
		Set<String> seenInTurn = new HashSet<>();
		List<Boolean> eligible = new ArrayList<>();
		for (String normalized : normalizedQuestions) {
			eligible.add(seenInTurn.add(normalized)
					&& !questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
							userId, normalized, startAt, endAt));
		}
		return eligible;
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
