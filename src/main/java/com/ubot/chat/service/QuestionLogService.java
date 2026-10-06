package com.ubot.chat.service;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class QuestionLogService {
	private final QuestionLogRepository questionLogRepository;
	private final IpRegionResolver ipRegionResolver;

	@Value("${ranking.window-minutes}")
	private int rankingWindowMinutes;

	@Transactional
	QuestionLog saveAndFlush(Long userId, Long conversationId, String question, String answer, String userIp){

		RegionSido regionSido = ipRegionResolver.resolve(userIp).orElse(null);

		String normalizedQuestion = question
				.strip()
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.replaceAll("[?!.,~]+", "")
				.replaceAll("\\s+", " ")
				.strip();


		// 게스트 질문은 세션을 새로 만들기 쉬워 랭킹 조작 비용이 낮으므로 실시간 검색어 집계에서 제외합니다.
		// 로그인 이후 생성된 회원 질문부터 집계에 포함합니다.

		boolean rankingEligible = false;

		if(userId != null) {
			String key = userId + ":" + normalizedQuestion;
			long lockKey = key.hashCode();

			questionLogRepository.acquireAdvisoryLock(lockKey);

			LocalDateTime endAt = LocalDateTime.now();
			LocalDateTime startAt = endAt.minusMinutes(rankingWindowMinutes);

			rankingEligible = !questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
					userId,
					normalizedQuestion,
					startAt,
					endAt);
		}

		QuestionLog questionLog = QuestionLog.builder()
				.userId(userId)
				.conversationId(conversationId)
				.userQuestion(question)
				.normalizedQuestion(normalizedQuestion)
				.answer(answer)
				.rankingEligible(rankingEligible)
				.region(regionSido)
				.build();

		return  questionLogRepository.saveAndFlush(questionLog);
	}
}
