package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ubot.outbox.OutboxEventType;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("통신 지도 Outbox 핸들러")
class TelemapOutboxHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("급증 질문 이벤트의 질문 로그 ID를 등록 서비스에 전달한다")
    void handlesQuestionEvent() {
        TelemapSurgingQuestionService service = mock(TelemapSurgingQuestionService.class);
        SurgingQuestionOutboxHandler handler = new SurgingQuestionOutboxHandler(service);

        handler.handle(JSON.readTree("{\"questionLogId\":17}"));

        assertThat(handler.type()).isEqualTo(OutboxEventType.SURGING_QUESTION);
        verify(service).registerQuestion(17L);
    }

    @Test
    @DisplayName("급증 알림 이벤트의 알림 ID를 제목 생성 서비스에 전달한다")
    void handlesAlertEvent() {
        TelemapAlertLabelService service = mock(TelemapAlertLabelService.class);
        SurgingAlertLabelOutboxHandler handler = new SurgingAlertLabelOutboxHandler(service);

        handler.handle(JSON.readTree("{\"alertId\":23}"));

        assertThat(handler.type()).isEqualTo(OutboxEventType.SURGING_ALERT_LABEL);
        verify(service).labelAndNotify(23L);
    }

    @ParameterizedTest
    @MethodSource("invalidQuestionPayloads")
    @DisplayName("질문 로그 ID가 없는 이벤트를 거부한다")
    void rejectsInvalidQuestionPayload(JsonNode payload) {
        SurgingQuestionOutboxHandler handler = new SurgingQuestionOutboxHandler(
                mock(TelemapSurgingQuestionService.class));

        assertInvalidEvent(() -> handler.handle(payload));
    }

    @ParameterizedTest
    @MethodSource("invalidAlertPayloads")
    @DisplayName("알림 ID가 없는 이벤트를 거부한다")
    void rejectsInvalidAlertPayload(JsonNode payload) {
        SurgingAlertLabelOutboxHandler handler = new SurgingAlertLabelOutboxHandler(
                mock(TelemapAlertLabelService.class));

        assertInvalidEvent(() -> handler.handle(payload));
    }

    static Stream<JsonNode> invalidQuestionPayloads() {
        return Stream.of(JSON.readTree("{}"), JSON.readTree("{\"questionLogId\":null}"));
    }

    static Stream<JsonNode> invalidAlertPayloads() {
        return Stream.of(JSON.readTree("{}"), JSON.readTree("{\"alertId\":null}"));
    }

    private static void assertInvalidEvent(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TelemapException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(TelemapErrorCode.INVALID_OUTBOX_EVENT));
    }
}
