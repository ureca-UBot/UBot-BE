package com.ubot.notification.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.notification.repository.NotificationRepository;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.entity.SurgingAlert;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("급증 감지 관리자 알림 생성")
class SurgingAlertNotificationServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(repository);

    @Test
    @DisplayName("관리자별 dedupe key와 급증 알림 ID를 포함해 알림을 저장한다")
    void createsOneNotificationPerAdministrator() {
        SurgingAlert alert = mock(SurgingAlert.class);
        when(alert.getId()).thenReturn(10L);
        when(alert.getSubject()).thenReturn("서울 5G 장애 문의 급증");
        when(alert.getRegion()).thenReturn(RegionSido.SEOUL);
        when(alert.getWindowMinutes()).thenReturn(10);
        when(alert.getQuestionCount()).thenReturn(100);

        service.createSurgingAlertNotifications(alert, List.of(1L, 2L));

        verify(repository).insertSurgingAlertIfAbsent(
                eq(1L), eq("SURGING_ALERT"), eq("[급증 감지] 서울 5G 장애 문의 급증"),
                eq("SEOUL 지역에서 최근 10분간 유사 문의 100건이 감지되었습니다."),
                eq(10L), eq("SURGING_ALERT:10:1"), any(LocalDateTime.class));
        verify(repository).insertSurgingAlertIfAbsent(
                eq(2L), eq("SURGING_ALERT"), eq("[급증 감지] 서울 5G 장애 문의 급증"),
                eq("SEOUL 지역에서 최근 10분간 유사 문의 100건이 감지되었습니다."),
                eq(10L), eq("SURGING_ALERT:10:2"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("관리자가 없으면 알림을 저장하지 않는다")
    void doesNothingWithoutAdministrators() {
        SurgingAlert alert = mock(SurgingAlert.class);
        when(alert.getSubject()).thenReturn("급증 제목");
        when(alert.getRegion()).thenReturn(RegionSido.SEOUL);
        when(alert.getWindowMinutes()).thenReturn(10);
        when(alert.getQuestionCount()).thenReturn(100);

        service.createSurgingAlertNotifications(alert, List.of());

        verify(repository, never()).insertSurgingAlertIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }
}
