package com.ubot.guardreview.worker;

import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.Source;
import static com.ubot.guardreview.model.ReviewModels.Vector;

import com.ubot.global.config.AppRole;
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
		try {
			if ("VALIDATION".equals(repository.run(id).kind())) {
				var request = reviews.getTestRequest(id);
				if (!request.datasetVersion().equals(validator.datasetVersion()))
					throw new IllegalStateException("검증 자료 버전이 변경됐습니다.");
				var result = validator.validate(request.candidates(), request.words(), validator.dataset(),
						request.datasetVersion());
				work.finishValidation(id, token, JSON.writeValueAsString(result), true);
			} else {
				Source source = repository.nextSource(id);
				if (source != null) {
					try {
						var suggestions = analyzer.analyze(source);
						if (suggestions == null || suggestions.size() > 20)
							throw new IllegalArgumentException("분석 후보 수를 확인해 주세요.");
						var proposals = new ArrayList<ReviewWorkService.Proposal>();
						for (var suggestion : suggestions) {
							ReviewService.validateSuggestion(source, suggestion);
							Vector vector = null;
							if (suggestion.kind() == Kind.WARNING) {
								try {
									vector = embedding.embed(suggestion.expression());
								} catch (RuntimeException e) {
									log.warn("위험 경고는 유지하고 의미 묶기를 생략합니다: 실행ID={}, 오류유형={}", id,
											e.getClass().getSimpleName());
								}
							}
							proposals.add(new ReviewWorkService.Proposal(suggestion, vector));
						}
						work.finishSource(id, token, source, proposals, true);
					} catch (RuntimeException e) {
						log.warn("검토 자료 분석에 실패했습니다: 실행ID={}, 항목ID={}, 오류유형={}", id, source.id(),
								e.getClass().getSimpleName());
						work.finishSource(id, token, source, java.util.List.of(), false);
					}
				}
				work.release(id, token);
				alerts.flush();
			}
		} catch (RuntimeException e) {
			log.warn("검토 실행을 완료하지 못했습니다: 실행ID={}, 오류유형={}", id, e.getClass().getSimpleName());
			// lease 소유권 확인 후에만 실패를 기록합니다. 다른 Worker의 결과는 덮어쓰지 않습니다.
			try {
				work.finishValidation(id, token, "{\"error\":\"REVIEW_FAILED\"}", false);
			} catch (RuntimeException lost) {
				log.warn("검토 작업 소유권을 잃었습니다: 실행ID={}", id);
			}
		}
	}
}
