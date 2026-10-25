package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.guardreview.repository.ReviewRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ReviewCaptureServiceTest {

	ReviewRepository repository = mock(ReviewRepository.class);
	PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	ReviewCaptureService capture = new ReviewCaptureService(repository, manager);

	void transactionReady() {
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
	}

	@Test
	void 생성_응답은_바깥_실패와_독립된_트랜잭션에_저장한다() {
		transactionReady();
		UUID key = UUID.randomUUID();
		when(repository.captureResponse(key, 9, "전체 답변")).thenReturn(15L);
		assertThat(capture.captureResponse(key, 9L, "전체 답변")).isEqualTo(15);
		var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(manager).getTransaction(definition.capture());
		assertThat(definition.getValue().getPropagationBehavior())
				.isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		verify(manager).commit(any());
	}

	@Test
	void 원문이나_시도연결이_없으면_가짜_수집_기록을_만들지않는다() {
		assertThat(capture.captureResponse(UUID.randomUUID(), null, "답변")).isNull();
		assertThat(capture.captureResponse(UUID.randomUUID(), 9L, " ")).isNull();
		capture.recordFailure(null, null, "FORMAT_ERROR");
		verifyNoInteractions(repository, manager);
	}

	@Test
	void 수집_DB_장애가_답변생성이나_기존_거절을_새_오류로_바꾸지않는다() {
		transactionReady();
		when(repository.captureResponse(any(), anyLong(), anyString()))
				.thenThrow(new IllegalStateException("synthetic DB failure"));
		assertThat(capture.captureResponse(UUID.randomUUID(), 9L, "답변")).isNull();
		verify(manager).rollback(any());
	}

	@Test
	void 거절한_답변은_최종답변_없이_사유와_함께_저장한다() {
		transactionReady();
		capture.recordGuardResult(15L, "위험한 원답변",
				new OutputGuardResult(null, true, Set.of(OutputGuardResult.Reason.POLICY_VIOLATION)));
		verify(repository).recordCaptureResult(15, "위험한 원답변", null, "REJECTED", "[\"POLICY_VIOLATION\"]", "CODE");
	}

	@Test
	void 일부제거와_정상통과를_본문_비교로_구분한다() {
		transactionReady();
		capture.recordGuardResult(1L, "정상. 위험.", new OutputGuardResult("정상.", false, Set.of()));
		capture.recordGuardResult(2L, "정상.", new OutputGuardResult("정상.", false, Set.of()));
		verify(repository).recordCaptureResult(1, "정상. 위험.", "정상.", "FILTERED", "[]", "CODE");
		verify(repository).recordCaptureResult(2, "정상.", "정상.", "PASS", "[]", "CODE");
	}

	@Test
	void 판정기록_장애에도_원래_흐름에_예외를_추가하지않는다() {
		transactionReady();
		doThrow(new IllegalStateException("synthetic write failure")).when(repository).recordCaptureResult(anyLong(),
				any(), any(), any(), any(), any());
		assertThatCode(() -> capture.recordFailure(1L, "답변", "UNCLASSIFIED")).doesNotThrowAnyException();
	}

	@Test
	void 보류는_장애와_구분하고_원본사유와_단계를_보존한다() {
		transactionReady();
		capture.recordGuardDecision(3L, "모델 답변", ReviewCaptureService.Decision.REVIEW,
				OutputGuardResult.Stage.LLM, java.util.List.of("INSUFFICIENT_EVIDENCE"));
		verify(repository).recordCaptureResult(3, "모델 답변", null, "REVIEW", "[\"INSUFFICIENT_EVIDENCE\"]", "LLM");
	}

	@Test
	void 검사하지_않은_답변을_통과로_기록하지_않는다() {
		transactionReady();
		capture.recordGuardResult(4L, "원본", new OutputGuardResult("최종 답변", false, Set.of(), false,
				OutputGuardResult.Stage.DISABLED));
		verify(repository).recordCaptureResult(4, "원본", "최종 답변", "SKIPPED", "[]", "DISABLED");
	}
}
