package com.ubot.guardreview.service;

import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.OutputGuardResult.Stage;
import com.ubot.guardreview.repository.ReviewRepository;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/** 답변 생성·거절 트랜잭션과 분리해 수집합니다. 저장 오류로 기존 가드 판정을 바꾸지 않습니다. */
@Service
@Slf4j
public class ReviewCaptureService {

	private final ReviewRepository repository;
	private final TransactionTemplate transaction;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	public enum Decision { REJECTED, REVIEW }

	public ReviewCaptureService(ReviewRepository repository, PlatformTransactionManager manager) {
		this.repository = repository;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public Long captureResponse(UUID captureId, Long attemptId, String rawResponse) {
		if (attemptId == null || !StringUtils.hasText(rawResponse))
			return null;
		try {
			return transaction.execute(status -> repository.captureResponse(captureId, attemptId, rawResponse));
		} catch (RuntimeException exception) {
			log.warn("가드 전 답변 수집에 실패했습니다: 시도ID={}, 오류유형={}", attemptId, exception.getClass().getSimpleName());
			return null;
		}
	}

	public void recordGuardResult(Long captureId, String originalAnswer, OutputGuardResult result) {
		if (captureId == null)
			return;
		String outcome = result.stage() == Stage.DISABLED ? "SKIPPED"
				: result.rejected() ? "REJECTED" : java.util.Objects.equals(originalAnswer, result.answer()) ? "PASS" : "FILTERED";
		record(captureId, originalAnswer, result.answer(), outcome,
				result.reasons().stream().map(Enum::name).sorted().toList(), result.stage().name());
	}

	/** 가드 보류·거절과 기술 오류를 구분합니다. 차단된 최종 답변은 보존하지 않습니다. */
	public void recordGuardDecision(Long captureId, String originalAnswer, Decision decision, Stage stage,
			List<String> reasons) {
		if (captureId != null)
			record(captureId, originalAnswer, null, decision.name(), reasons, stage.name());
	}

	public void recordFailure(Long captureId, String originalAnswer, String reason) {
		if (captureId != null)
			record(captureId, originalAnswer, null, "ERROR", List.of(reason), null);
	}

	private void record(long id, String originalAnswer, String finalAnswer, String outcome, List<String> reasons,
			String stage) {
		try {
			transaction.executeWithoutResult(status -> repository.recordCaptureResult(id, originalAnswer, finalAnswer,
					outcome, JSON.writeValueAsString(reasons), stage));
		} catch (RuntimeException exception) {
			log.warn("수집 답변의 가드 결과 기록에 실패했습니다: 수집ID={}, 오류유형={}", id, exception.getClass().getSimpleName());
		}
	}
}
