package com.ubot.monitoring.service;

import com.ubot.faq.entity.Faq;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.monitoring.dto.model.detail.FaqBasicInfo;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrend;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPoint;
import com.ubot.monitoring.dto.model.detail.FaqTrendWindow;
import com.ubot.monitoring.dto.response.FaqDetailResponse;
import com.ubot.monitoring.dto.response.FaqMonitoringResponse;
import com.ubot.monitoring.repository.FaqMonitoringDetailQueryRepository;
import com.ubot.monitoring.repository.FaqMonitoringRankingQueryRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FaqMonitoringService {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int RANKING_LIMIT = 20;
	private final FaqRepository faqRepository;
	private final FaqMonitoringDetailQueryRepository detailQueryRepository;
	private final FaqMonitoringRankingQueryRepository rankingQueryRepository;

	@Transactional(readOnly = true)
	public FaqMonitoringResponse getMonitoringData(LocalDateTime startAt, LocalDateTime endAt) {
		if (!startAt.isBefore(endAt)) {
			throw new IllegalArgumentException("startAt은 endAt보다 이전이어야 합니다.");
		}

		return new FaqMonitoringResponse(
				rankingQueryRepository.findUnansweredQuestionSummary(startAt, endAt),
				rankingQueryRepository.findUsageRanking(startAt, endAt, RANKING_LIMIT),
				rankingQueryRepository.findSimilarityRanking(startAt, endAt, RANKING_LIMIT)
		);
	}

	@Transactional(readOnly = true)
	public FaqDetailResponse getFaqDetail(
			Long faqId,
			FaqUsageTrendPeriod period,
			LocalDateTime asOf
	) {
		Faq faq = faqRepository.findActiveById(faqId)
				.orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
		LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
		FaqTrendWindow window = FaqTrendWindow.from(period, asOf, now);
		List<FaqUsageTrendPoint> points = detailQueryRepository.findUsageTrend(faqId, window, now);

		return new FaqDetailResponse(
				toBasicInfo(faq),
				new FaqUsageTrend(
						window.period(), window.startAt(), window.endAt(),
						points.stream().mapToLong(FaqUsageTrendPoint::count).sum(), points
				),
				detailQueryRepository.findSimilaritySummary(faqId, window),
				detailQueryRepository.findTop1Summary(faqId, window),
				detailQueryRepository.findTopGapSummary(faqId, window),
				detailQueryRepository.findCooccurringFaqs(faqId, window)
		);
	}

	private FaqBasicInfo toBasicInfo(Faq faq) {
		return new FaqBasicInfo(
				faq.getId(), faq.getQuestion(), faq.getAnswer(), faq.getFaqCategory().getName(),
				faq.getIntent(), faq.getVersion(), faq.getCreatedAt(), faq.getUpdatedAt()
		);
	}
}
