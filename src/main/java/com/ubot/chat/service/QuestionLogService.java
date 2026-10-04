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


		// Todo: Guest 정책이 확정되고 난후, guest 질문의 랭킹 산정 중복 처리를 구현한다.

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
