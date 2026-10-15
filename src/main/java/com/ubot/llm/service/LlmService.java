package com.ubot.llm.service;

import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
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

    public LlmService(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.permits = null;
    }

    @Autowired
    public LlmService(
            LlmClient llmClient,
            @Value("${LLM_MAX_CONCURRENT:${app.chat.worker.concurrency:4}}") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalStateException("LLM_MAX_CONCURRENT는 1 이상이어야 합니다: " + maxConcurrent);
        }
        this.llmClient = llmClient;
        this.permits = new Semaphore(maxConcurrent, true);
        log.info("LLM 동시 호출 상한을 정했습니다: 상한={}", maxConcurrent);
    }

    /** 검색과 프롬프트 구성은 호출자가 완료한 뒤 전달합니다. */
	public LlmResponseDto generateAnswer(LlmRequestDto request) {
        // API 호출 전에 메시지 누락·공백·마지막 역할을 확인합니다.
        validateRequest(request);
        acquirePermit();
        // 실제 모델 통신은 LlmClient 구현체에 맡깁니다.
		long startedAt = System.nanoTime();
		try {
			LlmResponseDto response = llmClient.generateAnswer(request);
			if(response == null)
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);

			log.debug("LLM 요청을 완료했습니다: 처리시간={}ms, 응답길이={}", elapsedMillis(startedAt),
					response.answer() == null ? 0 : response.answer().length());
			return response;
		} catch (LlmException exception) {
			log.warn("LLM 요청에 실패했습니다: 오류코드={}, 오류메시지={}, 처리시간={}ms",
					exception.getErrorCode().getCode(), exception.getErrorCode().getMessage(), elapsedMillis(startedAt));
			throw exception;
		} finally {
			if (permits != null) {
				permits.release();
			}
		}
    }

	// 기다리다 취소되면 모델을 부르지 않음
	private void acquirePermit() {
		if (permits == null || permits.tryAcquire()) {
			return;
		}
		long waitStartedAt = System.nanoTime();
		try {
			permits.acquire();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CancellationException("LLM 호출 차례를 기다리다 취소되었습니다.");
		}
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
