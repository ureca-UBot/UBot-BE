package com.ubot.notification.entity;

import com.ubot.notification.enums.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notifications")
public class Notification {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "notification_id")
	private Long notificationId;

	@Column(name = "user_id")
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type")
	private NotificationType type;

	@Column(name = "title")
	private String title;

	@Column(name = "message")
	private String message;

	@Column(name = "reservation_id")
	private Long reservationId;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	public static Notification create(Long userId, NotificationType type, String title, String message, Long reservationId){
		Notification notification = new Notification();
		notification.userId = userId;
		notification.type = type;
		notification.title = title;
		notification.message = message;
		notification.reservationId = reservationId;
		notification.createdAt = LocalDateTime.now();
		return notification;
	}

	public void read(){
		if(this.readAt == null){
			this.readAt = LocalDateTime.now();
		}
	}
}
