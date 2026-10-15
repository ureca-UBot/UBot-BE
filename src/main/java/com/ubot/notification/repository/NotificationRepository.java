package com.ubot.notification.repository;

import com.ubot.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
	List<Notification> findTop50ByUserIdOrderByCreatedAtDescNotificationIdDesc(Long userId);

	Optional<Notification> findByNotificationIdAndUserId(Long notificationId, Long userId);

	long countByUserIdAndReadAtIsNull(Long userId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Notification n set n.readAt = :readAt where n.userId = :userId and n.readAt is null")
	int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}