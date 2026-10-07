package com.ubot.notification.service;

import com.ubot.notification.dto.response.NotificationResponseDto;
import com.ubot.notification.entity.Notification;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.exception.NotificationErrorCode;
import com.ubot.notification.exception.NotificationException;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.reservation.entity.StoreReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class NotificationService {
	private static final DateTimeFormatter VISIT_AT_FORMAT = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN);

	private final NotificationRepository notificationRepository;

	@Transactional
	public void createReservationNotification(StoreReservation reservation, NotificationType type){
		boolean confirmed = type == NotificationType.RESERVATION_CONFIRMED;
		String message = reservation.getStore().getStoreName() + " "
				+ reservation.getVisitAt().format(VISIT_AT_FORMAT) + " "
				+ reservation.getPurpose().getDisplayName()
				+ (confirmed ? " 예약이 완료되었습니다." : " 예약이 취소되었습니다.");
		notificationRepository.save(Notification.create(
				reservation.getUserId(),
				type,
				confirmed ? "방문 예약 완료" : "방문 예약 취소",
				message,
				reservation.getReservationId()
		));
	}

	@Transactional(readOnly = true)
	public List<NotificationResponseDto> getMyNotificationList(Long userId){
		return notificationRepository.findTop50ByUserIdOrderByCreatedAtDescNotificationIdDesc(userId).stream()
				.map(NotificationResponseDto::from)
				.toList();
	}

	@Transactional
	public NotificationResponseDto readNotification(Long userId, Long notificationId){
		Notification notification = notificationRepository.findByNotificationIdAndUserId(notificationId, userId)
				.orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		notification.read();
		return NotificationResponseDto.from(notification);
	}
}
