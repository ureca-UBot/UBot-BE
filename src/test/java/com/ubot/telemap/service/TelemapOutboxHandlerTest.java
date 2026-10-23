package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ubot.outbox.OutboxEventType;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Telemap outbox handler")
class TelemapOutboxHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void handlesAlertEvent() {
        TelemapAlertLabelService service = mock(TelemapAlertLabelService.class);
        SurgingAlertLabelOutboxHandler handler = new SurgingAlertLabelOutboxHandler(service);

        handler.handle(JSON.readTree("{\"alertId\":23}"));

        assertThat(handler.type()).isEqualTo(OutboxEventType.SURGING_ALERT_LABEL);
        verify(service).labelAndNotify(23L);
    }

    @ParameterizedTest
    @MethodSource("invalidAlertPayloads")
    void rejectsInvalidAlertPayload(JsonNode payload) {
        SurgingAlertLabelOutboxHandler handler = new SurgingAlertLabelOutboxHandler(
                mock(TelemapAlertLabelService.class));

        assertThatThrownBy(() -> handler.handle(payload))
                .isInstanceOfSatisfying(TelemapException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(TelemapErrorCode.INVALID_OUTBOX_EVENT));
    }

    static Stream<JsonNode> invalidAlertPayloads() {
        return Stream.of(JSON.readTree("{}"), JSON.readTree("{\"alertId\":null}"));
    }
}
