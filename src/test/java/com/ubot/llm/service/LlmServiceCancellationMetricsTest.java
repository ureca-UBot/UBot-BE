package com.ubot.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.progress.AnswerProgress;
import com.ubot.ai.progress.AnswerProgress.Phase;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LlmServiceCancellationMetricsTest {

    private final LlmClient client = mock(LlmClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmService service = new LlmService(client, 1, registry);
    private final LlmRequestDto request = new LlmRequestDto(List.of(
            new LlmMessageRequestDto(LlmMessageRole.USER, "질문")));

    enum RequestKind {
        ANSWER, JUDGMENT, TIMED_JUDGMENT
    }

    @ParameterizedTest
    @EnumSource(RequestKind.class)
    void preCancelledRequestDoesNotUseModelOrPermit(RequestKind kind) {
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> {
                switch (kind) {
                    case ANSWER -> service.generateAnswer(request);
                    case JUDGMENT -> service.generateJudgment(request);
                    case TIMED_JUDGMENT -> service.generateJudgment(request, 100);
                }
            }).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verifyNoInteractions(client);
        assertThat(registry.get("llm.concurrency.in.use").gauge().value()).isZero();
        assertThat(registry.find("llm.requests").timer()).isNull();
        assertThat(registry.find("llm.permit.wait").timer()).isNull();
    }

    @Test
    void cancellationBeforeModelStartKeepsMetricsAndReleasesPermit() {
        var phases = new ArrayList<Phase>();
        try {
            assertThatThrownBy(() -> AnswerProgress.observe(phase -> {
                phases.add(phase);
                if (phase == Phase.WAITING_FOR_MODEL) {
                    Thread.currentThread().interrupt();
                }
            }, () -> service.generateAnswer(request))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(phases).containsExactly(Phase.WAITING_FOR_MODEL);
        verifyNoInteractions(client);
        assertThat(registry.get("llm.requests").tag("kind", "answer").tag("outcome", "cancelled")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("llm.concurrency.in.use").gauge().value()).isZero();

        when(client.generateAnswer(request)).thenReturn(new LlmResponseDto("정상 답변"));
        assertThat(service.generateAnswer(request).answer()).isEqualTo("정상 답변");
        assertThat(registry.get("llm.requests").tag("kind", "answer").tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void clientCancellationIsCountedSeparatelyAndNextRequestCanProceed() {
        when(client.generateAnswer(request))
                .thenThrow(new CancellationException("사용자 중단"))
                .thenReturn(new LlmResponseDto("정상 답변"));

        assertThatThrownBy(() -> service.generateJudgment(request, 100))
                .isInstanceOf(CancellationException.class);
        assertThat(registry.get("llm.requests").tag("kind", "judgment").tag("outcome", "cancelled")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("llm.concurrency.in.use").gauge().value()).isZero();
        assertThat(service.generateAnswer(request).answer()).isEqualTo("정상 답변");
    }
}
