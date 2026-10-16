package com.ubot.notification.service;

import com.ubot.billing.entity.BillingRecord;
import com.ubot.notification.dto.response.NotificationResponseDto;
import com.ubot.notification.dto.response.ReadAllResponseDto;
import com.ubot.notification.dto.response.UnreadCountResponseDto;
import com.ubot.notification.entity.Notification;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.enums.ReservationReminderStage;
import com.ubot.notification.exception.NotificationErrorCode;
import com.ubot.notification.exception.NotificationException;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.reservation.entity.StoreReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final DateTimeFormatter VISIT_AT_FORMAT = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm",
            Locale.KOREAN);
    private static final DateTimeFormatter DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);
    private final NotificationRepository notificationRepository;

    @Transactional
    public void createReservationNotification(StoreReservation reservation, NotificationType type) {
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
                reservation.getReservationId()));
    }

    /**
     * 방문 예약 리마인더
     * 같은 예약의 같은 단계 알림이 이미 있으면 만들지 않고 false를 반환
     * 24시간 전 단계는 방문일이 오늘이면 "오늘", 아니면 "내일"로, 6시간·1시간 전 단계는 실제 남은 시간으로 안내
     */
    @Transactional
    public boolean createReservationReminder(StoreReservation reservation, ReservationReminderStage stage,
            LocalDateTime now) {
        String notice = switch (stage) {
            case D1 -> "방문이 " + (reservation.getVisitAt().toLocalDate().equals(now.toLocalDate()) ? "오늘" : "내일")
                    + "입니다.";
            case H6, H1 -> "방문이 " + remainingText(Duration.between(now, reservation.getVisitAt())) + " 남았습니다.";
        };
        String message = reservation.getStore().getStoreName() + " "
                + reservation.getVisitAt().format(VISIT_AT_FORMAT) + " "
                + reservation.getPurpose().getDisplayName() + " " + notice;
        return notificationRepository.insertIfAbsent(
                reservation.getUserId(),
                NotificationType.RESERVATION_REMINDER.name(),
                "방문 예약 리마인더",
                message,
                reservation.getReservationId(),
                NotificationType.RESERVATION_REMINDER.name() + ":" + reservation.getReservationId() + ":"
                        + stage.name(),
                LocalDateTime.now()) > 0;
    }

    // 남은 시간을 분 단위로 반올림해 "5시간 30분", "6시간", "30분"처럼 표기
    private static String remainingText(Duration remaining) {
        long minutes = Math.max(1, remaining.plusSeconds(30).toMinutes());
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + "분";
        }
        return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
    }

    /**
     * 요금 납부 리마인더(납부 기한 임박·연체). 같은 청구의 같은 종류 알림이 이미 있으면 만들지 않고 false를 반환
     * 알림에 적는 금액은 총 청구액이 아니라 아직 내지 않은 금액
     */
    @Transactional
    public boolean createBillingReminder(BillingRecord billing, NotificationType type, LocalDate today) {
        String amount = String.format(Locale.KOREA, "%,d", billing.getTotalAmount() - billing.getPaidAmount());
        String subject = billing.getBillingMonth().getMonthValue() + "월 요금 " + amount + "원";
        String dueDate = billing.getDueDate().format(DUE_DATE_FORMAT);

        String title;
        String message;
        if (type == NotificationType.BILLING_DUE_SOON) {
            long daysLeft = ChronoUnit.DAYS.between(today, billing.getDueDate());
            title = "납부 기한 안내";
            message = daysLeft == 0
                    ? subject + "의 납부 기한이 오늘입니다."
                    : subject + "의 납부 기한이 " + daysLeft + "일 남았습니다. (기한: " + dueDate + ")";
        } else if (type == NotificationType.BILLING_OVERDUE) {
            title = "요금 연체 안내";
            message = subject + "이 납부 기한(" + dueDate + ")을 지났습니다. 빠른 납부를 부탁드립니다.";
        } else {
            throw new IllegalArgumentException("요금 리마인더 유형이 아닙니다: " + type);
        }

        return notificationRepository.insertWithoutReservationIfAbsent(
                billing.getSubscription().getUser().getId(),
                type.name(),
                title,
                message,
                type.name() + ":" + billing.getBillingId(),
                LocalDateTime.now()) > 0;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponseDto> getMyNotificationList(Long userId) {
        return notificationRepository.findTop50ByUserIdOrderByCreatedAtDescNotificationIdDesc(userId).stream()
                .map(NotificationResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UnreadCountResponseDto getUnreadCount(Long userId) {
        return new UnreadCountResponseDto(notificationRepository.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional
    public NotificationResponseDto readNotification(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByNotificationIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.read();
        return NotificationResponseDto.from(notification);
    }

    @Transactional
    public ReadAllResponseDto readAllNotifications(Long userId) {
        return new ReadAllResponseDto(notificationRepository.markAllRead(userId, LocalDateTime.now()));
    }
}