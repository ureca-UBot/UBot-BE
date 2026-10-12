package com.ubot.monitoring.service;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.monitoring.dto.model.detail.FaqBasicInfo;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrend;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPoint;
import com.ubot.monitoring.dto.model.detail.FaqTrendWindow;
import com.ubot.monitoring.dto.model.improvelayer.FaqImprovementItem;
import com.ubot.monitoring.dto.model.improvelayer.FaqImprovementMetric;
import com.ubot.monitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.dto.model.similaritylayer.FaqSimilarityRanking;
import com.ubot.monitoring.dto.model.usagelayer.FaqUsageRanking;
import com.ubot.monitoring.dto.response.FaqDetailResponse;
import com.ubot.monitoring.dto.response.FaqImprovementResponse;
import com.ubot.monitoring.dto.response.FaqMonitoringResponse;
import com.ubot.monitoring.repository.FaqMonitoringDetailQueryRepository;
import com.ubot.monitoring.repository.FaqMonitoringImprovementQueryRepository;
import com.ubot.monitoring.repository.FaqMonitoringRankingQueryRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqMonitoringService {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int RANKING_LIMIT = 20;
	private final FaqRepository faqRepository;
	private final FaqMonitoringDetailQueryRepository detailQueryRepository;
	private final FaqMonitoringImprovementQueryRepository improvementQueryRepository;
	private final FaqMonitoringRankingQueryRepository rankingQueryRepository;

	@Value("${faq-monitoring.improvement.top-k}")
	private int improvementTopK;

	@Value("${faq-monitoring.improvement.low-similarity-threshold}")
	private double lowSimilarityThreshold;

	@Value("${faq-monitoring.improvement.low-gap-rate-threshold}")
	private double lowGapRateThreshold;

	@Value("${faq-monitoring.improvement.unused-days}")
	private int unusedDays;

	@Value("${faq-monitoring.improvement.minimum-top-k-samples}")
	private int minimumTopKSampleCount;

	@Value("${faq-monitoring.improvement.minimum-top1-samples}")
	private int minimumTop1SampleCount;

	@Transactional(readOnly = true)
	public FaqMonitoringResponse getMonitoringData(LocalDateTime startAt, LocalDateTime endAt) {
		if (!startAt.isBefore(endAt)) {
			log.warn("FAQ 모니터링 조회 기간이 유효하지 않습니다. startAt={}, endAt={}", startAt, endAt);
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER, "startAt은 endAt보다 이전이어야 합니다.");
		}

		FaqMonitoringResponse response = new FaqMonitoringResponse(
				rankingQueryRepository.findUnansweredQuestionSummary(startAt, endAt),
				rankingQueryRepository.findUsageRanking(startAt, endAt, RANKING_LIMIT),
				rankingQueryRepository.findSimilarityRanking(startAt, endAt, RANKING_LIMIT)
		);

		log.info(
				"FAQ 모니터링 집계를 조회했습니다. startAt={}, endAt={}, 전체 질문 수={}, 미처리 질문 수={}",
				startAt,
				endAt,
				response.unansweredQuestionSummary().totalQuestionCount(),
				response.unansweredQuestionSummary().unansweredQuestionCount()
		);
		return response;
	}

	@Transactional(readOnly = true)
	public FaqImprovementResponse getFaqImprovements() {
		LocalDateTime evaluatedAt = LocalDateTime.now(SERVICE_ZONE);
		LocalDateTime recentUsageStartAt = evaluatedAt.minusDays(unusedDays);
		List<FaqImprovementItem> items = improvementQueryRepository
				.findCurrentVersionMetrics(evaluatedAt, recentUsageStartAt, improvementTopK)
				.stream()
				.map(metric -> toImprovementItem(metric, evaluatedAt))
				.flatMap(java.util.Optional::stream)
				.sorted(Comparator
						.comparingInt((FaqImprovementItem item) -> item.improvementTypes().size()).reversed()
						.thenComparing(Comparator.comparingLong(FaqImprovementItem::topKCount).reversed())
						.thenComparingDouble(FaqImprovementItem::averageSimilarity))
				.toList();

		log.info(
				"개선 필요 FAQ를 조회했습니다. evaluatedAt={}, 결과 수={}, 유사도 기준={}, 낮은 Gap 비율 기준={}, 미사용 기간={}일",
				evaluatedAt,
				items.size(),
				lowSimilarityThreshold,
				lowGapRateThreshold,
				unusedDays
		);
		return new FaqImprovementResponse(evaluatedAt, items);
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

		FaqDetailResponse response = new FaqDetailResponse(
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

		log.info(
				"FAQ 상세 모니터링을 조회했습니다. faqId={}, period={}, asOf={}, windowStartAt={}, windowEndAt={}, 사용량={}, 유사도 표본 수={}, Top-1 횟수={}, Gap 표본 수={}, 함께 등장한 FAQ 수={}",
				faqId,
				period,
				asOf,
				window.startAt(),
				window.endAt(),
				response.usageTrend().totalCount(),
				response.similarity().sampleCount(),
				response.top1().top1Count(),
				response.topGap().sampleCount(),
				response.cooccurringFaqs().size()
		);
		return response;
	}

	private FaqBasicInfo toBasicInfo(Faq faq) {
		return new FaqBasicInfo(
				faq.getId(), faq.getQuestion(), faq.getAnswer(), faq.getFaqCategory().getName(),
				faq.getIntent(), faq.getVersion(), faq.getCreatedAt(), faq.getUpdatedAt()
		);
	}

	private java.util.Optional<FaqImprovementItem> toImprovementItem(
			FaqImprovementMetric metric,
			LocalDateTime evaluatedAt
	) {
		List<FaqImprovementType> types = new ArrayList<>();
		if (metric.topKCount() >= minimumTopKSampleCount
				&& metric.averageSimilarity() < lowSimilarityThreshold) {
			types.add(FaqImprovementType.LOW_RAG_QUALITY);
		}
		if (metric.top1SampleCount() >= minimumTop1SampleCount
				&& metric.lowGapRate() >= lowGapRateThreshold) {
			types.add(FaqImprovementType.DUPLICATE_SUSPECTED);
		}
		if (!metric.versionStartedAt().plusDays(unusedDays).isAfter(evaluatedAt)
				&& metric.recentUsageCount() == 0) {
			types.add(FaqImprovementType.LOW_USAGE);
		}

		if (types.isEmpty()) {
			return java.util.Optional.empty();
		}

		return java.util.Optional.of(new FaqImprovementItem(
				metric.faqId(), metric.question(), metric.intent(), metric.faqVersion(), metric.versionStartedAt(),
				types, metric.topKCount(), metric.averageSimilarity(), metric.top1SampleCount(),
				metric.recentUsageCount(), metric.averageTopGap(), metric.lowGapRate()
		));
	}
}
