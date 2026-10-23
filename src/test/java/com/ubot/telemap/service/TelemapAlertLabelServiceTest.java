package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import com.ubot.notification.service.NotificationService;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

@DisplayName("급증 알림 제목 생성 서비스")
class TelemapAlertLabelServiceTest {

    private final SurgingAlertRepository alerts = mock(SurgingAlertRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final LlmService llm = mock(LlmService.class);
    private final TelemapProperties properties = new TelemapProperties();
    private TelemapAlertLabelService service;

    @BeforeEach
    void setUp() {
        properties.setMaxQuestionsForSubject(2);
        service = new TelemapAlertLabelService(alerts, users, notifications, llm, properties,
                new ByteArrayResource("제목을 JSON으로 생성하세요".getBytes()));
    }

    @Test
    @DisplayName("LLM이 만든 유효한 제목을 저장하고 활성 관리자 모두에게 알림을 보낸다")
    void labelsAndNotifiesAdministrators() {
        SurgingAlert alert = alert(10L, null, 3);
        when(alerts.findById(10L)).thenReturn(Optional.of(alert));
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("{\"subject\":\"서울 5G 장애 문의 급증\"}"));
        when(users.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN)).thenReturn(List.of(
                User.builder().id(1L).role(UserRole.ADMIN).build(),
                User.builder().id(2L).role(UserRole.ADMIN).build()));

        service.labelAndNotify(10L);

        assertThat(alert.getSubject()).isEqualTo("서울 5G 장애 문의 급증");
        verify(notifications).createSurgingAlertNotifications(alert, List.of(1L, 2L));

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llm).generateAnswer(request.capture());
        assertThat(request.getValue().hasResponseSchema()).isTrue();
        assertThat(request.getValue().messages()).hasSize(2);
        assertThat(request.getValue().messages().get(1).content())
                .contains("지역: SEOUL", "집계 시간: 최근 10분", "집계 건수: 3", "질문 2", "질문 3")
                .doesNotContain("질문 1");
    }

    @Test
    @DisplayName("이미 제목이 있으면 LLM을 호출하지 않고 알림만 생성한다")
    void reusesExistingSubject() {
        SurgingAlert alert = alert(11L, "기존 제목", 1);
        when(alerts.findById(11L)).thenReturn(Optional.of(alert));
        when(users.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN)).thenReturn(List.of());

        service.labelAndNotify(11L);

        verify(llm, never()).generateAnswer(any());
        verify(notifications).createSurgingAlertNotifications(alert, List.of());
    }

    @ParameterizedTest
    @MethodSource("invalidLlmOutputs")
    @DisplayName("유효하지 않은 LLM 응답이면 지역 기반 기본 제목을 사용한다")
    void fallsBackForInvalidLlmOutput(String output) {
        SurgingAlert alert = alert(12L, null, 1);
        when(alerts.findById(12L)).thenReturn(Optional.of(alert));
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto(output));
        when(users.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN)).thenReturn(List.of());

        service.labelAndNotify(12L);

        assertThat(alert.getSubject()).isEqualTo("SEOUL 지역 유사 문의 급증");
    }

    @Test
    @DisplayName("LLM 호출 실패 시에도 기본 제목으로 관리자 알림을 만든다")
    void fallsBackWhenLlmFails() {
        SurgingAlert alert = alert(13L, null, 1);
        when(alerts.findById(13L)).thenReturn(Optional.of(alert));
        when(llm.generateAnswer(any())).thenThrow(new IllegalStateException("LLM unavailable"));
        when(users.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN)).thenReturn(List.of());

        service.labelAndNotify(13L);

        assertThat(alert.getSubject()).isEqualTo("SEOUL 지역 유사 문의 급증");
        verify(notifications).createSurgingAlertNotifications(alert, List.of());
    }

    @Test
    @DisplayName("존재하지 않는 급증 알림을 거부한다")
    void rejectsMissingAlert() {
        when(alerts.findById(99L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.labelAndNotify(99L))
                .isInstanceOfSatisfying(TelemapException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(TelemapErrorCode.SURGING_ALERT_NOT_FOUND));
    }

    static Stream<String> invalidLlmOutputs() {
        return Stream.of("", "not-json", "{}", "{\"subject\":null}", "{\"subject\":\"   \"}",
                "{\"subject\":\"" + "가".repeat(81) + "\"}");
    }

    private static SurgingAlert alert(long id, String subject, int questionCount) {
        List<SurgingQuestionSnapshot> snapshots = java.util.stream.LongStream.rangeClosed(1, questionCount)
                .mapToObj(index -> new SurgingQuestionSnapshot(index, "질문 " + index, LocalDateTime.now()))
                .toList();
        SurgingAlert alert = SurgingAlert.create(RegionSido.SEOUL, snapshots, 10);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "id", id);
        if (subject != null) alert.updateSubject(subject);
        return alert;
    }
}
