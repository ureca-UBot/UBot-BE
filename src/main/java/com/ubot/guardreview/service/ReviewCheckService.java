package com.ubot.guardreview.service;

import com.ubot.guardreview.dto.response.ReviewCheckResponseDto;
import com.ubot.guardreview.dto.response.ReviewCheckDetailResponseDto;

import static com.ubot.guardreview.model.ReviewCheckModels.*;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.common.PageResponseDto;
import com.ubot.guardreview.exception.ReviewErrorCode;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.repository.ReviewCheckRepository;
import com.ubot.llm.dto.request.LlmRequestDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 판정은 바꾸지 않고 짧은 기록만 저장합니다. 추출·임베딩·14B 호출은 기존 관리자 Worker의 몫입니다. */
@Slf4j
@Service
public class ReviewCheckService {
	private final ReviewCheckRepository repository;
	private final TransactionTemplate transaction;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final ThreadLocal<AttemptChecks> currentAttempt = new ThreadLocal<>();
	private record AttemptChecks(Long id, java.util.EnumSet<Origin> observed) {
	}

	public ReviewCheckService(ReviewCheckRepository repository, PlatformTransactionManager manager) {
		this.repository = repository;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaction.setTimeout(2);
	}

	public void inputCode(String question, Outcome outcome, String reason) {
		// 접수 시 정상 질문의 동기 중복 INSERT는 생략합니다. 생성 답변과 LLM 검사 PASS 수집은 유지합니다.
		if (outcome == Outcome.PASS || outcome == Outcome.SKIPPED) return;
		record(null, Origin.INPUT_CODE, outcome, question, null, null, List.of(reason), null, Map.of());
	}

	public void inputLlm(Long attemptId, String question, List<ConversationTurn> history, Outcome outcome,
			List<String> reasons, LlmRequestDto request) {
		Map<String, Object> context = request == null
				? Map.of("history", history == null ? List.of() : history) : requestData(request);
		record(attemptId, Origin.INPUT_LLM, outcome, question, null, null, reasons, null, context);
	}

	public void outputCode(String answer, OutputGuardResult result, AnswerMaterials materials, StoreMapResult stores) {
		Outcome outcome = result.stage() == OutputGuardResult.Stage.DISABLED ? Outcome.SKIPPED
				: result.rejected() ? Outcome.REJECTED
				: result.contentRemoved() || !java.util.Objects.equals(answer, result.answer()) ? Outcome.FILTERED : Outcome.PASS;
		record(materials.reviewAttemptId(), Origin.OUTPUT_CODE, outcome, materials.question(), answer,
				result.rejected() ? null : result.answer(), result.reasons().stream().map(Enum::name).toList(),
				null, outputContext(materials, stores));
	}

	public void outputCodeError(String answer, AnswerMaterials materials, StoreMapResult stores) {
		record(materials.reviewAttemptId(), Origin.OUTPUT_CODE, Outcome.ERROR, materials.question(), answer,
				null, List.of("CHECK_FAILED"), null, outputContext(materials, stores));
	}

	public void outputLlm(String answer, AnswerMaterials materials, StoreMapResult stores, Outcome outcome,
			List<String> reasons, String explanation, LlmRequestDto request) {
		record(materials.reviewAttemptId(), Origin.OUTPUT_LLM, outcome, materials.question(), answer,
				outcome == Outcome.PASS ? answer : null, reasons, explanation,
				request == null ? outputContext(materials, stores) : requestData(request));
	}

	private Map<String, Object> outputContext(AnswerMaterials materials, StoreMapResult stores) {
		return ReviewContextSnapshot.output(materials, stores);
	}
	private Map<String, Object> requestData(LlmRequestDto request) {
		return ReviewContextSnapshot.request(request);
	}

	public void beginAttempt(Long attemptId) {
		currentAttempt.set(new AttemptChecks(attemptId, java.util.EnumSet.noneOf(Origin.class)));
	}

	/** 원문 검색·질문 정리 OFF·검색 실패 등으로 실제 호출되지 않은 검사는 허용으로 집계하지 않습니다. */
	public void finishAttempt(Long attemptId, String question) {
		AttemptChecks attempt = currentAttempt.get();
		currentAttempt.remove();
		if (attemptId == null || attempt == null || !java.util.Objects.equals(attempt.id(), attemptId)) return;
		var skipped = java.util.EnumSet.of(Origin.INPUT_LLM, Origin.OUTPUT_CODE, Origin.OUTPUT_LLM);
		skipped.removeAll(attempt.observed());
		if (skipped.isEmpty()) return;
		try {
			transaction.executeWithoutResult(status -> {
				repository.saveSkippedSummary(attemptId, question, skipped.stream().sorted().toList());
			});
		} catch (RuntimeException exception) {
			log.warn("가드 미실행 기록 저장 실패: 오류유형={}", exception.getClass().getSimpleName());
		}
	}

	private void record(Long attemptId, Origin origin, Outcome outcome, String question, String answer,
			String finalAnswer, List<String> reasons, String explanation, Map<String, Object> context) {
		AttemptChecks attempt = currentAttempt.get();
		if (attempt != null && java.util.Objects.equals(attempt.id(), attemptId)) attempt.observed().add(origin);
		try {
			String snapshot = JSON.writeValueAsString(context);
			transaction.executeWithoutResult(status -> repository.save(UUID.randomUUID(), attemptId, origin, outcome,
					reasons, question, answer, finalAnswer, explanation, snapshot));
		} catch (RuntimeException exception) {
			// 저장 장애로 기존 가드의 허용·차단을 바꾸거나 사용자 본문을 로그에 노출하지 않습니다.
			log.warn("가드 검토 기록 저장 실패: 출처={}, 결과={}, 오류유형={}", origin, outcome, exception.getClass().getSimpleName());
		}
	}

	@Transactional(readOnly = true)
	public PageResponseDto<ReviewCheckResponseDto> page(Origin origin, Outcome outcome, int page, int size) {
		if (page < 0 || !Set.of(10, 20, 50).contains(size)) throw new ReviewException(ReviewErrorCode.INVALID);
		return repository.page(origin, outcome, page, size);
	}
	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public PageResponseDto<ReviewCheckResponseDto> pageFiltered(List<Origin> origins, List<Outcome> outcomes,
			int page, int size) {
		if (page < 0 || !Set.of(10, 20, 50).contains(size) || origins == null || outcomes == null
				|| origins.size() > Origin.values().length || outcomes.size() > Outcome.values().length
				|| origins.stream().anyMatch(java.util.Objects::isNull)
				|| outcomes.stream().anyMatch(java.util.Objects::isNull)) throw new ReviewException(ReviewErrorCode.INVALID);
		return repository.pageFiltered(origins.stream().distinct().sorted().toList(),
				outcomes.stream().distinct().sorted().toList(), page, size);
	}

	@Transactional(readOnly = true)
	public ReviewCheckDetailResponseDto detail(long id) {
		return repository.detail(id);
	}
}
