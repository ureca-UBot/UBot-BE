package com.ubot.notification.repository;

import com.ubot.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
	List<Notification> findTop50ByUserIdOrderByCreatedAtDescNotificationIdDesc(Long userId);

	Optional<Notification> findByNotificationIdAndUserId(Long notificationId, Long userId);
}
