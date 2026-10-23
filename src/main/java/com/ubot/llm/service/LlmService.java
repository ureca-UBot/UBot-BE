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
import java.util.Optional;
import java.util.function.Supplier;
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
    private final Semaphore backgroundPermits = new Semaphore(1);
    private final int backgroundAdmissionPermits;
    // 지표를 보지 않는 테스트는 전역 레지스트리를 씀. 등록된 저장소가 없으면 기록하지 않음
    private final MeterRegistry metrics;

    public LlmService(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.permits = null;
        this.backgroundAdmissionPermits = 1;
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
        this.backgroundAdmissionPermits = Math.min(2, maxConcurrent);
        this.metrics = metrics;
        // 포화도 = in.use / limit. waiting이 계속 0보다 크면 모델 서버 앞에서 줄을 서고 있음
        metrics.gauge("llm.concurrency.limit", permits, ignored -> maxConcurrent);
        metrics.gauge("llm.concurrency.in.use", permits, semaphore -> maxConcurrent - semaphore.availablePermits());
        metrics.gauge("llm.concurrency.waiting", permits, Semaphore::getQueueLength);
        log.info("LLM 동시 호출 상한을 정했습니다: 상한={}", maxConcurrent);
    }

    /** 검색과 프롬프트 구성은 호출자가 완료한 뒤 전달합니다. */
	public LlmResponseDto generateAnswer(LlmRequestDto request) {
		return generate(request, true, null);
    }

    /** 판정은 검색 준비 단계이므로 답변 생성 진행 단계를 보고하지 않습니다. */
    public LlmResponseDto generateJudgment(LlmRequestDto request) {
		return generate(request, false, null);
    }

    /** 관리자 분석은 줄을 서지 않습니다. 여유가 없으면 호출자가 다음 주기로 미룹니다. */
    public Optional<LlmResponseDto> tryGenerateBackgroundJudgment(LlmRequestDto request) {
        return tryGenerateBackgroundJudgment(request, () -> llmClient.generateAnswer(request));
    }

    /** 전용 관리자 모델도 기존 채팅 우선 입장 제어와 지표를 공유합니다. */
    public Optional<LlmResponseDto> tryGenerateBackgroundJudgment(
            LlmRequestDto request, Supplier<LlmResponseDto> modelCall) {
        if (modelCall == null) {
            throw new IllegalArgumentException("백그라운드 모델 호출이 필요합니다.");
        }
        checkCancellation();
        validateRequest(request);
        if (!backgroundPermits.tryAcquire()) {
            return Optional.empty();
        }
        try {
            if (!tryAcquireBackgroundPermit()) {
                return Optional.empty();
            }
            return Optional.of(generateWithPermit(false, modelCall));
        } finally {
            backgroundPermits.release();
        }
    }

    private boolean tryAcquireBackgroundPermit() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("관리자 분석 LLM 호출이 취소되었습니다.");
        }
        if (permits == null) {
            return true;
        }
        try {
            // 공정한 즉시 시도로 대기 중인 채팅을 앞지르지 않고, 상한 2 이상에서는 마지막 빈 슬롯을 남깁니다.
            if (!permits.tryAcquire(backgroundAdmissionPermits, 0, TimeUnit.NANOSECONDS)) {
                return false;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("관리자 분석 LLM 호출이 취소되었습니다.");
        }
        // 시작 조건만 원자적으로 확인하고 실제 모델 호출에는 기존 슬롯 하나만 사용합니다.
        if (backgroundAdmissionPermits > 1) {
            permits.release(backgroundAdmissionPermits - 1);
        }
        return true;
    }


	/** 지정된 시간 안에 모델 호출 슬롯을 얻지 못하면 요청 과다 오류를 반환합니다. */
	public LlmResponseDto generateJudgment(LlmRequestDto request, long permitWaitTimeoutMillis) {
		if (permitWaitTimeoutMillis <= 0) {
			throw new IllegalArgumentException("LLM 호출 슬롯 대기 시간은 0보다 커야 합니다.");
		}
		return generate(request, false, permitWaitTimeoutMillis);
	}

	private LlmResponseDto generate(
			LlmRequestDto request,
			boolean reportAnswerProgress,
			Long permitWaitTimeoutMillis
	) {
        // API 호출 전에 메시지 누락·공백·마지막 역할을 확인합니다.
        checkCancellation();
        validateRequest(request);
        if (reportAnswerProgress) {
            AnswerProgress.report(Phase.WAITING_FOR_MODEL);
        }
		acquirePermit(permitWaitTimeoutMillis);
        return generateWithPermit(request, reportAnswerProgress);
    }

    private LlmResponseDto generateWithPermit(LlmRequestDto request, boolean reportAnswerProgress) {
        return generateWithPermit(reportAnswerProgress, () -> llmClient.generateAnswer(request));
    }

    private LlmResponseDto generateWithPermit(boolean reportAnswerProgress, Supplier<LlmResponseDto> modelCall) {
        // 실제 모델 통신은 LlmClient 구현체에 맡깁니다. 차례를 기다린 시간은 llm.permit.wait로 따로 셈
		long startedAt = System.nanoTime();
		String outcome = "error";
		try {
            checkCancellation();
			if (reportAnswerProgress) {
				AnswerProgress.report(Phase.GENERATING);
			}
			LlmResponseDto response = modelCall.get();
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

	private void checkCancellation() {
		if (Thread.currentThread().isInterrupted()) {
			throw new CancellationException("LLM 요청이 취소되었습니다.");
		}
	}

	// 기다리다 취소되면 모델을 부르지 않음
	private void acquirePermit(Long waitTimeoutMillis) {
		if (permits == null) {
			return;
		}
		if (permits.tryAcquire()) {
			metrics.timer("llm.permit.wait").record(0, TimeUnit.NANOSECONDS);
			return;
		}
		long waitStartedAt = System.nanoTime();
		try {
			if (waitTimeoutMillis == null) {
				permits.acquire();
			} else if (!permits.tryAcquire(waitTimeoutMillis, TimeUnit.MILLISECONDS)) {
				// 대기 한도 안에 차례를 못 얻어 거절한 호출. 포화 신호로 따로 셈
				metrics.counter("llm.permit.rejected").increment();
				log.warn("LLM 호출 슬롯 대기 시간이 초과되었습니다: 대기한도={}ms", waitTimeoutMillis);
				throw new LlmException(LlmErrorCode.LLM_BUSY);
			}
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
