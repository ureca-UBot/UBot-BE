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

    List<Notification> findTop50ByUserIdAndTypeOrderByCreatedAtDescNotificationIdDesc(
            Long userId, com.ubot.notification.enums.NotificationType type);

    Optional<Notification> findByNotificationIdAndUserId(Long notificationId, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :readAt where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);

    // dedupe_key가 같은 알림이 이미 있으면 아무것도 하지 않고 0을 반환. 스케줄러가 다시 실행돼도 중복 생성되지 않음.
    @Modifying
    @Query(value = """
            INSERT INTO notifications (user_id, type, title, message, reservation_id, dedupe_key, created_at)
            VALUES (:userId, :type, :title, :message, :reservationId, :dedupeKey, :createdAt)
            ON CONFLICT (dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("title") String title,
            @Param("message") String message,
            @Param("reservationId") Long reservationId,
            @Param("dedupeKey") String dedupeKey,
            @Param("createdAt") LocalDateTime createdAt);

    // 예약과 무관한 알림(요금 등)용. reservation_id에 null을 바인딩하면 DB 드라이버가 타입을 추론하지 못할 수 있어 컬럼
    // 자체를 뺀다.
    @Modifying
    @Query(value = """
            INSERT INTO notifications (user_id, type, title, message, dedupe_key, created_at)
            VALUES (:userId, :type, :title, :message, :dedupeKey, :createdAt)
            ON CONFLICT (dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int insertWithoutReservationIfAbsent(
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("title") String title,
            @Param("message") String message,
            @Param("dedupeKey") String dedupeKey,
            @Param("createdAt") LocalDateTime createdAt);
}