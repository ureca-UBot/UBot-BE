package com.ubot.notification.dto.response;

import com.ubot.notification.entity.Notification;
import com.ubot.notification.enums.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponseDto(
		Long notificationId,
		NotificationType type,
		String title,
		String message,
		Long reservationId,
		boolean read,
		LocalDateTime createdAt
) {
	public static NotificationResponseDto from(Notification notification){
		return new NotificationResponseDto(
				notification.getNotificationId(),
				notification.getType(),
				notification.getTitle(),
				notification.getMessage(),
				notification.getReservationId(),
				notification.getReadAt() != null,
				notification.getCreatedAt()
		);
	}
}
