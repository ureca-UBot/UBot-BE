package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.user.entity.User;
import com.ubot.user.exception.UserException;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@DisplayName("통신 지도 관리자 알림 서비스")
class TelemapAlertServiceTest {

    private final SurgingAlertRepository alerts = mock(SurgingAlertRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TelemapAlertService service = new TelemapAlertService(alerts, users);

    @Test
    @DisplayName("상태와 지역으로 필터링하고 생성일과 ID 역순으로 페이지 조회한다")
    void getsFilteredAlertsNewestFirst() {
        SurgingAlert alert = alert(5L, SurgingAlertStatus.PENDING, null);
        when(alerts.findAllByFilters(eq(SurgingAlertStatus.PENDING), eq(RegionSido.SEOUL), any()))
                .thenReturn(new PageImpl<>(List.of(alert), PageRequest.of(1, 20), 21));

        var response = service.getAlerts(SurgingAlertStatus.PENDING, RegionSido.SEOUL, 1, 20);

        assertThat(response.content()).singleElement().satisfies(item -> {
            assertThat(item.alertId()).isEqualTo(5L);
            assertThat(item.status()).isEqualTo(SurgingAlertStatus.PENDING);
            assertThat(item.processedAt()).isNull();
        });
        assertThat(response.totalElements()).isEqualTo(21);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(alerts).findAllByFilters(eq(SurgingAlertStatus.PENDING), eq(RegionSido.SEOUL), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    @DisplayName("대기 중 알림을 처리한 관리자와 처리 시간을 반환한다")
    void marksAlertProcessed() {
        User administrator = User.builder().id(7L).build();
        SurgingAlert pending = alert(6L, SurgingAlertStatus.PENDING, null);
        SurgingAlert processed = alert(6L, SurgingAlertStatus.PROCESSED, administrator);
        when(users.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(administrator));
        when(alerts.findById(6L)).thenReturn(Optional.of(pending), Optional.of(processed));
        when(alerts.markProcessedIfPending(eq(6L), eq(administrator), any(LocalDateTime.class))).thenReturn(1);

        var response = service.markProcessed(6L, 7L);

        verify(alerts).markProcessedIfPending(eq(6L), eq(administrator), any(LocalDateTime.class));
        assertThat(response.status()).isEqualTo(SurgingAlertStatus.PROCESSED);
        assertThat(response.processedById()).isEqualTo(7L);
        assertThat(response.processedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 알림 조회를 거부한다")
    void rejectsMissingAlert() {
        when(alerts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAlert(99L))
                .isInstanceOfSatisfying(TelemapException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(TelemapErrorCode.SURGING_ALERT_NOT_FOUND));
    }

    @Test
    @DisplayName("존재하지 않거나 탈퇴한 관리자의 처리 요청을 거부한다")
    void rejectsMissingAdministrator() {
        when(alerts.findById(1L)).thenReturn(Optional.of(alert(1L, SurgingAlertStatus.PENDING, null)));
        when(users.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markProcessed(1L, 77L)).isInstanceOf(UserException.class);
    }

    private static SurgingAlert alert(long id, SurgingAlertStatus status, User updatedBy) {
        SurgingAlert alert = SurgingAlert.create(RegionSido.SEOUL,
                List.of(new SurgingQuestionSnapshot(1L, "질문", LocalDateTime.now())), 10);
        alert.updateSubject("급증 제목");
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "id", id);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "status", status);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "updatedBy", updatedBy);
        if (status == SurgingAlertStatus.PROCESSED) {
            org.springframework.test.util.ReflectionTestUtils.setField(alert, "updatedAt", LocalDateTime.now());
        }
        return alert;
    }
}
