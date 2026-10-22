package com.ubot.llm.service;

import com.ubot.common.ErrorCode;
import com.ubot.llm.client.LlmClient;
import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class LlmService {

    private final LlmClient llmClient;
    // 모델 서버 동시 호출 상한. 시간 초과로 슬롯을 먼저 돌려줘도 끝나지 않은 호출이 허가를 쥐고 있어 상한을 넘지 않음
    // null이면 제한 없음(단위 테스트)
    private final Semaphore permits;
    // 지표를 보지 않는 테스트는 전역 레지스트리를 씀. 등록된 저장소가 없으면 기록하지 않음
    private final MeterRegistry metrics;

    public LlmService(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.permits = null;
        this.metrics = Metrics.globalRegistry;
    }

    public LlmService(LlmClient llmClient, int maxConcurrent) {
        this(llmClient, maxConcurrent, Metrics.globalRegistry);
    }

    // 지표 저장소가 없는 설정 테스트 문맥에서도 뜨도록 전역 레지스트리(기록 안 함)로 대신함
    @Autowired
    public LlmService(
            LlmClient llmClient,
            @Value("${LLM_MAX_CONCURRENT:${app.chat.worker.concurrency:4}}") int maxConcurrent,
            ObjectProvider<MeterRegistry> metrics) {
        this(llmClient, maxConcurrent, metrics.getIfAvailable(() -> Metrics.globalRegistry));
    }

    public LlmService(LlmClient llmClient, int maxConcurrent, MeterRegistry metrics) {
        if (maxConcurrent < 1) {
            throw new IllegalStateException("LLM_MAX_CONCURRENT는 1 이상이어야 합니다: " + maxConcurrent);
        }
        this.llmClient = llmClient;
        this.permits = new Semaphore(maxConcurrent, true);
        this.metrics = metrics;
        // 포화도 = in.use / limit. waiting이 계속 0보다 크면 모델 서버 앞에서 줄을 서고 있음
        metrics.gauge("llm.concurrency.limit", permits, ignored -> maxConcurrent);
        metrics.gauge("llm.concurrency.in.use", permits, semaphore -> maxConcurrent - semaphore.availablePermits());
        metrics.gauge("llm.concurrency.waiting", permits, Semaphore::getQueueLength);
        log.info("LLM 동시 호출 상한을 정했습니다: 상한={}", maxConcurrent);
    }

    /** 검색과 프롬프트 구성은 호출자가 완료한 뒤 전달합니다. */
	public LlmResponseDto generateAnswer(LlmRequestDto request) {
        return generate(request, true);
    }

    /** 판정은 검색 준비 단계이므로 답변 생성 진행 단계를 보고하지 않습니다. */
    public LlmResponseDto generateJudgment(LlmRequestDto request) {
        return generate(request, false);
    }

    private LlmResponseDto generate(LlmRequestDto request, boolean reportAnswerProgress) {
        // API 호출 전에 메시지 누락·공백·마지막 역할을 확인합니다.
        validateRequest(request);
        if (reportAnswerProgress) {
            AnswerProgress.report(Phase.WAITING_FOR_MODEL);
        }
        acquirePermit();
        // 실제 모델 통신은 LlmClient 구현체에 맡깁니다. 차례를 기다린 시간은 llm.permit.wait로 따로 셈
		long startedAt = System.nanoTime();
		String outcome = "error";
		try {
			if (reportAnswerProgress) {
				AnswerProgress.report(Phase.GENERATING);
			}
			LlmResponseDto response = llmClient.generateAnswer(request);
			if(response == null)
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);

			outcome = "success";
			log.debug("LLM 요청을 완료했습니다: 처리시간={}ms, 응답길이={}", elapsedMillis(startedAt),
					response.answer() == null ? 0 : response.answer().length());
			return response;
		} catch (LlmException exception) {
			outcome = outcomeOf(exception.getErrorCode());
			log.warn("LLM 요청에 실패했습니다: 오류코드={}, 오류메시지={}, 처리시간={}ms",
					exception.getErrorCode().getCode(), exception.getErrorCode().getMessage(), elapsedMillis(startedAt));
			throw exception;
		} catch (CancellationException exception) {
			outcome = "cancelled";
			throw exception;
		} finally {
			metrics.timer("llm.requests", "kind", reportAnswerProgress ? "answer" : "judgment", "outcome", outcome)
					.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
			if (permits != null) {
				permits.release();
			}
		}
    }

	// 지표 태그로 쓸 실패 종류. 시간 초과율은 outcome=timeout, 오류율은 success·cancelled를 뺀 비율
	private static String outcomeOf(ErrorCode errorCode) {
		if (errorCode == LlmErrorCode.LLM_TIMEOUT) return "timeout";
		if (errorCode == LlmErrorCode.LLM_SERVICE_UNAVAILABLE) return "unavailable";
		if (errorCode == LlmErrorCode.LLM_RESPONSE_INVALID) return "invalid_response";
		return "error";
	}

	// 기다리다 취소되면 모델을 부르지 않음
	private void acquirePermit() {
		if (permits == null) {
			return;
		}
		if (permits.tryAcquire()) {
			metrics.timer("llm.permit.wait").record(0, TimeUnit.NANOSECONDS);
			return;
		}
		long waitStartedAt = System.nanoTime();
		try {
			permits.acquire();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CancellationException("LLM 호출 차례를 기다리다 취소되었습니다.");
		}
		metrics.timer("llm.permit.wait").record(System.nanoTime() - waitStartedAt, TimeUnit.NANOSECONDS);
		log.debug("LLM 호출 차례를 기다렸습니다: 대기시간={}ms", elapsedMillis(waitStartedAt));
	}

	/** AI_MODE로 선택된 구현체가 답변 생성에 쓰는 모델 이름을 반환합니다. */
	public String getModelName() {
		return llmClient.getModelName();
	}

    private void validateRequest(LlmRequestDto request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
        }

        for (var message : request.messages()) {
            if (message == null || message.role() == null || !StringUtils.hasText(message.content())) {
                throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
			}
		}

        if (request.messages().getLast().role() != LlmMessageRole.USER) {
            throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
        }

        // 도구 메서드가 ToolContext를 받으면 Spring AI는 빈 문맥으로 도구를 실행하지 않고 예외를 던집니다.
        if (request.hasTools() && request.toolContext().isEmpty()) {
            throw new LlmException(LlmErrorCode.LLM_REQUEST_INVALID);
        }
    }

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
