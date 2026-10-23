package com.ubot.guardreview.worker;

import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.Source;
import static com.ubot.guardreview.model.ReviewModels.Vector;

import com.ubot.global.config.AppRole;
import com.ubot.guardreview.exception.ReviewAnalysisDeferredException;
import com.ubot.guardreview.exception.ReviewLeaseLostException;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.guardreview.service.*;
import java.util.ArrayList;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression(AppRole.RUNS_WORKER)
public class ReviewWorker {
	private final ReviewRepository repository;
	private final ReviewAnalyzer analyzer;
	private final ReviewEmbedding embedding;
	private final ReviewService reviews;
	private final ReviewWorkService work;
	private final ReviewValidationService validator;
	private final ReviewAlertService alerts;
	private final ReviewLeaseService leases;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Scheduled(fixedDelay = 5000)
	public void deliverAlerts() {
		try {
			alerts.flush();
		} catch (RuntimeException e) {
			log.warn("관리자 경고 알림을 다시 시도합니다: 오류유형={}", e.getClass().getSimpleName());
		}
	}

	@Scheduled(fixedDelay = 2000)
	public void poll() {
		UUID token = UUID.randomUUID();
		Long id = repository.claim(token);
		if (id == null)
			return;
		String kind = null;
		try (var lease = leases.maintain(id, token)) {
			kind = repository.runSummary(id).kind();
			lease.check();
			if ("VALIDATION".equals(kind)) {
				var request = reviews.getTestRequest(id);
				if (!request.datasetVersion().equals(validator.datasetVersion())
						|| !request.baseVersion().equals(validator.baseVersion(request.words()))) {
					log.warn("검사 기준 버전이 다른 요청은 실행하지 않습니다: 실행ID={}", id);
					throw new IllegalStateException("검사 규칙 또는 검증 자료 버전이 변경됐습니다.");
				}
				var result = validator.validate(request.candidates(), request.words(), validator.dataset(),
						request.datasetVersion(), lease::check);
				lease.check();
				work.finishValidation(id, token, JSON.writeValueAsString(result), true);
			} else {
				Source source = repository.nextSource(id);
				if (source != null && !analyze(id, token, source, lease))
					return;
				lease.check();
				work.release(id, token);
				deliverAlerts();
			}
		} catch (ReviewLeaseLostException e) {
			log.warn("관리자 검토 작업 소유권을 잃어 결과를 저장하지 않습니다: 실행ID={}", id);
		} catch (RuntimeException e) {
			log.warn("검토 실행을 완료하지 못했습니다: 실행ID={}, 오류유형={}", id, e.getClass().getSimpleName());
			try {
				if ("ANALYSIS".equals(kind))
					work.failAnalysis(id, token);
				else if ("VALIDATION".equals(kind))
					work.finishValidation(id, token, "{\"error\":\"REVIEW_FAILED\"}", false);
			} catch (RuntimeException lost) {
				log.warn("검토 작업 소유권을 확인할 수 없습니다: 실행ID={}", id);
			}
		}
	}
	private boolean analyze(long id, UUID token, Source source, ReviewLeaseService.Lease lease) {
		try {
			var suggestions = analyzer.analyze(source);
			lease.check();
			if (suggestions == null || suggestions.size() > 20)
				throw new IllegalArgumentException("분석 후보 수를 확인해 주세요.");
			var proposals = new ArrayList<ReviewWorkService.Proposal>();
			for (var suggestion : suggestions) {
				lease.check();
				ReviewService.validateSuggestion(source, suggestion);
				suggestion = ReviewWordPolicy.forReview(suggestion);
				Vector vector = null;
				if (suggestion.kind() == Kind.WARNING) {
					try {
						vector = embedding.embed(suggestion.expression());
					} catch (RuntimeException e) {
						log.warn("위험 경고는 유지하고 의미 묶기를 생략합니다: 실행ID={}, 오류유형={}", id, e.getClass().getSimpleName());
					}
				}
				proposals.add(new ReviewWorkService.Proposal(suggestion, vector));
			}
			lease.check();
			work.finishSource(id, token, source, proposals, true);
		} catch (ReviewAnalysisDeferredException e) {
			log.debug("사용자 채팅을 위해 관리자 분석을 다음 주기로 미룹니다: 실행ID={}, 항목ID={}", id, source.id());
		} catch (ReviewLeaseLostException e) {
			throw e;
		} catch (RuntimeException e) {
			lease.check();
			log.warn("검토 자료 분석에 실패했습니다: 실행ID={}, 항목ID={}, 오류유형={}", id, source.id(), e.getClass().getSimpleName());
			if (e instanceof LlmException llm && (llm.getErrorCode() == LlmErrorCode.LLM_SERVICE_UNAVAILABLE
					|| llm.getErrorCode() == LlmErrorCode.LLM_TIMEOUT)) {
				work.retryAnalysis(id, token, source, llm.getErrorCode().getCode());
				return false;
			}
			work.finishSource(id, token, source, java.util.List.of(), false);
		}
		return true;
	}
}
