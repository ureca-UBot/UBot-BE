package com.ubot.chat.service;

import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.ranking.enums.RegionSido;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class QuestionLogService {
	private final QuestionLogRepository questionLogRepository;
	private final IpRegionResolver ipRegionResolver;

	@Value("${ranking.window-minutes}")
	private int rankingWindowMinutes;

	QuestionLog saveAndFlush(Long userId, String question,String answer, String userIp){

		RegionSido regionSido = ipRegionResolver.resolve(userIp).orElse(null);

		String normalizedQuestion = question
				.strip()
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.replaceAll("[?!.,~]+", "");

		LocalDateTime endAt = LocalDateTime.now();
		LocalDateTime startAt = endAt.minusMinutes(rankingWindowMinutes);
		boolean rankingEligible = false;

		if(userId != null) {
			rankingEligible = !questionLogRepository.existsByUserIdAndNormalizedQuestionInRankingWindow(
					userId,
					normalizedQuestion,
					startAt,
					endAt);
		}
		else {
			rankingEligible = !questionLogRepository.existsByNormalizedQuestionInRankingWindow(
					normalizedQuestion,
					startAt,
					endAt
			);
		}

		QuestionLog questionLog = QuestionLog.builder()
				.userId(userId)
				.userQuestion(question)
				.normalizedQuestion(normalizedQuestion)
				.answer(answer)
				.rankingEligible(rankingEligible)
				.region(regionSido)
				.build();

		return  questionLogRepository.saveAndFlush(questionLog);
	}
}
