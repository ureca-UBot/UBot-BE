package com.ubot.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.notification.repository.NotificationRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("알림 서비스 단위 테스트")
class NotificationServiceTest {
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(notificationRepository);

    @Test
    @DisplayName("읽지 않은 알림 개수를 반환한다")
    void returnsUnreadCount() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(1L)).thenReturn(3L);

        assertThat(service.getUnreadCount(1L).unreadCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("전체 읽음 처리는 새로 읽음 처리한 개수를 반환한다")
    void readsAllNotifications() {
        when(notificationRepository.markAllRead(eq(1L), any(LocalDateTime.class))).thenReturn(4);

        assertThat(service.readAllNotifications(1L).updatedCount()).isEqualTo(4);
    }
}