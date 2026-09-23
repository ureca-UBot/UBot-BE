package com.ubot.chat.mvp.service;

import com.ubot.chat.mvp.config.ChatMvpSettings;
import com.ubot.chat.mvp.domain.ChatAttempt;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import com.ubot.chat.mvp.domain.ChatFailure;
import com.ubot.chat.mvp.exception.ChatMvpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatRequestReplayTest {
    private static final String KEY = "a".repeat(64);

    @ParameterizedTest
    @CsvSource({"PENDING,processing", "SUCCESS,completed"})
    void repeatedPostRecoversIdsWithoutSchedulingAnotherGeneration(String status, String event) throws Exception {
        var records = mock(ChatRecordService.class);
        var pipeline = mock(ChatAnswerPipeline.class);
        var runner = mock(ChatJobRunner.class);
        var settings = new ChatMvpSettings(Duration.ofSeconds(150), 4, 16, 3, .75, "llm", "embedding");
        var service = new ChatStreamService(records, pipeline, runner, settings);
        var attempt = new ChatAttempt(101, 10, 1, 20, "질문", 1, status, KEY,
                "SUCCESS".equals(status) ? "답변" : null, "FAIL".equals(status) ? "LLM_TIMEOUT" : null, null);
        when(runner.reserve()).thenReturn(true);
        when(records.retry(1, 20, 10, KEY)).thenReturn(new ChatRecordService.Claim(attempt, false));

        var mvc = MockMvcBuilders.standaloneSetup(new EmitterHarness(service, false)).build();
        var pending = mvc.perform(get("/emitter-harness")).andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:" + event, "\"sessionId\":20", "\"questionId\":10", "\"attemptCount\":1");
        assertThat(body).contains("\"idempotencyKey\":\"" + KEY + "\"");
        if ("PENDING".equals(status)) { assertThat(body).doesNotContain("event:failed", "event:completed"); }
        verifyNoInteractions(pipeline);
        verify(runner).reserve();
        verify(runner).release();
        verifyNoMoreInteractions(runner);
    }

    @Test void failureEventReturnsTheKeySavedByTheServer() throws Exception {
        var records = mock(ChatRecordService.class);
        var pipeline = mock(ChatAnswerPipeline.class);
        var runner = mock(ChatJobRunner.class);
        var service = new ChatStreamService(records, pipeline, runner,
                new ChatMvpSettings(Duration.ofSeconds(150), 4, 16, 3, .75, "llm", "embedding"));
        var pending = new ChatAttempt(101, 10, 1, 20, "질문", 1, "PENDING", KEY, null, null, null);
        var failed = new ChatAttempt(101, 10, 1, 20, "질문", 1, "FAIL", KEY, null, "LLM_TIMEOUT", "시간 초과");
        when(runner.reserve()).thenReturn(true);
        when(records.begin(1, 20, "질문")).thenReturn(new ChatRecordService.Claim(pending, true));
        when(pipeline.generate("질문")).thenReturn(ChatAnswerPipeline.Result.failed("LLM_TIMEOUT", "시간 초과"));
        when(records.fail(101, new ChatFailure("LLM_TIMEOUT", "시간 초과"))).thenReturn(failed);
        doAnswer(call -> { FutureTask<Void> task = call.getArgument(0); task.run(); return null; })
                .when(runner).execute(any());
        var mvc = MockMvcBuilders.standaloneSetup(new EmitterHarness(service, true)).build();
        var request = mvc.perform(get("/emitter-harness")).andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:processing", "event:failed", "\"retryable\":true",
                "\"idempotencyKey\":\"" + KEY + "\"");
        verify(records).begin(1, 20, "질문");
        verify(records).fail(101, new ChatFailure("LLM_TIMEOUT", "시간 초과"));
    }

    @Test void retryRejectsClientUuidOrMissingKeyBeforeDatabaseAndWorkerAccess() {
        var records = mock(ChatRecordService.class);
        var pipeline = mock(ChatAnswerPipeline.class);
        var runner = mock(ChatJobRunner.class);
        var service = new ChatStreamService(records, pipeline, runner,
                new ChatMvpSettings(Duration.ofSeconds(150), 4, 16, 3, .75, "llm", "embedding"));
        for (String key : new String[]{null, "", "1b3e57f0-e5c4-441d-bbc8-d41ea3b15c03"}) {
            assertThatThrownBy(() -> service.retry(1, 20, 10, key)).isInstanceOf(ChatMvpException.class);
        }
        verifyNoInteractions(records, runner, pipeline);
    }

    /** Tests the service's actual SSE serialization and completion, without starting HTTP servers. */
    @RestController
    static class EmitterHarness {
        private final ChatStreamService service;
        private final boolean initial;
        EmitterHarness(ChatStreamService service, boolean initial) { this.service = service; this.initial = initial; }
        @GetMapping(value = "/emitter-harness", produces = "text/event-stream")
        SseEmitter invoke() { return initial ? service.start(1, 20, "질문") : service.retry(1, 20, 10, KEY); }
    }
}
