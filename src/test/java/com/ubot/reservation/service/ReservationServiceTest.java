package com.ubot.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.notification.dto.response.NotificationResponseDto;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.service.NotificationService;
import com.ubot.reservation.dto.request.ReservationCreateRequestDto;
import com.ubot.reservation.dto.response.ReservationResponseDto;
import com.ubot.reservation.dto.response.ReservationSlotResponseDto;
import com.ubot.reservation.enums.ReservationPurpose;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.reservation.exception.ReservationErrorCode;
import com.ubot.reservation.exception.ReservationException;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("매장 방문 예약 통합 테스트")
class ReservationServiceTest {
	@Autowired private ReservationService reservationService;
	@Autowired private NotificationService notificationService;
	@Autowired private JdbcTemplate jdbcTemplate;

	private Long userId;
	private Long otherUserId;
	private Long storeId;
	private LocalDate visitDate;

	@BeforeEach
	void setUp() {
		userId = user();
		otherUserId = user();
		storeId = jdbcTemplate.queryForObject(
				"INSERT INTO stores (store_name, address, latitude, longitude, business_hours) VALUES (?, ?, 37.5, 127.0, '10:00-13:00') RETURNING store_id",
				Long.class, "예약테스트점-" + UUID.randomUUID(), "서울 테스트로 " + UUID.randomUUID());
		visitDate = LocalDate.now().plusDays(2);
		if (visitDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
			visitDate = visitDate.plusDays(1);
		}
	}

	@Test
	@DisplayName("영업시간을 1시간 단위로 나누고 예약된 시간은 예약 불가로 표시한다")
	void listsSlotsWithReservedTimeUnavailable() {
		reservationService.createReservation(userId, request(11));

		assertThat(reservationService.getReservationSlotList(storeId, visitDate))
				.extracting(ReservationSlotResponseDto::time, ReservationSlotResponseDto::available)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(LocalTime.of(10, 0), true),
						org.assertj.core.groups.Tuple.tuple(LocalTime.of(11, 0), false),
						org.assertj.core.groups.Tuple.tuple(LocalTime.of(12, 0), true));
	}

	@Test
	@DisplayName("일요일과 14일 이후 날짜는 예약 가능한 시간이 없다")
	void returnsNoSlotsForClosedDays() {
		LocalDate sunday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SUNDAY));

		assertThat(reservationService.getReservationSlotList(storeId, sunday)).isEmpty();
		assertThat(reservationService.getReservationSlotList(storeId, LocalDate.now().plusDays(15))).isEmpty();
	}

	@Test
	@DisplayName("예약하면 확인 알림이 함께 저장된다")
	void createsReservationWithNotification() {
		ReservationResponseDto result = reservationService.createReservation(userId, request(10));

		assertThat(result.status()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(result.purposeName()).isEqualTo("요금제 변경");
		assertThat(notificationService.getMyNotificationList(userId))
				.extracting(NotificationResponseDto::type, NotificationResponseDto::reservationId)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationType.RESERVATION_CONFIRMED, result.reservationId()));
	}

	@Test
	@DisplayName("이미 예약된 시간은 다른 사용자가 예약할 수 없다")
	void rejectsReservedSlot() {
		reservationService.createReservation(userId, request(10));

		assertError(() -> reservationService.createReservation(otherUserId, request(10)),
				ReservationErrorCode.RESERVATION_SLOT_UNAVAILABLE);
	}

	@Test
	@DisplayName("정시가 아니거나 영업시간 밖이거나 일요일이면 예약할 수 없다")
	void rejectsInvalidVisitTime() {
		LocalDate sunday = visitDate.with(TemporalAdjusters.next(DayOfWeek.SUNDAY));

		assertError(() -> reservationService.createReservation(userId, request(visitDate.atTime(10, 30))),
				ReservationErrorCode.INVALID_VISIT_TIME);
		assertError(() -> reservationService.createReservation(userId, request(visitDate.atTime(13, 0))),
				ReservationErrorCode.INVALID_VISIT_TIME);
		assertError(() -> reservationService.createReservation(userId, request(sunday.atTime(10, 0))),
				ReservationErrorCode.INVALID_VISIT_TIME);
		assertError(() -> reservationService.createReservation(userId, request(LocalDate.now().minusDays(1).atTime(10, 0))),
				ReservationErrorCode.INVALID_VISIT_TIME);
	}

	@Test
	@DisplayName("진행 중인 예약은 1인 3건까지만 가능하다")
	void limitsActiveReservations() {
		reservationService.createReservation(userId, request(10));
		reservationService.createReservation(userId, request(11));
		reservationService.createReservation(userId, request(12));
		LocalDate nextDate = visitDate.plusDays(1).getDayOfWeek() == DayOfWeek.SUNDAY ? visitDate.plusDays(2) : visitDate.plusDays(1);

		assertError(() -> reservationService.createReservation(userId, request(nextDate.atTime(10, 0))),
				ReservationErrorCode.RESERVATION_LIMIT_EXCEEDED);
	}

	@Test
	@DisplayName("예약을 취소하면 취소 알림이 남고 같은 시간을 다시 예약할 수 있다")
	void cancelsReservationAndReleasesSlot() {
		ReservationResponseDto reservation = reservationService.createReservation(userId, request(10));

		ReservationResponseDto canceled = reservationService.cancelReservation(userId, reservation.reservationId());

		assertThat(canceled.status()).isEqualTo(ReservationStatus.CANCELED);
		assertThat(canceled.canceledAt()).isNotNull();
		assertThat(notificationService.getMyNotificationList(userId))
				.extracting(NotificationResponseDto::type)
				.containsExactly(NotificationType.RESERVATION_CANCELED, NotificationType.RESERVATION_CONFIRMED);
		assertThat(reservationService.createReservation(otherUserId, request(10)).status())
				.isEqualTo(ReservationStatus.RESERVED);
	}

	@Test
	@DisplayName("다른 사용자의 예약은 취소할 수 없다")
	void cannotCancelOthersReservation() {
		ReservationResponseDto reservation = reservationService.createReservation(userId, request(10));

		assertError(() -> reservationService.cancelReservation(otherUserId, reservation.reservationId()),
				ReservationErrorCode.RESERVATION_NOT_FOUND);
	}

	@Test
	@DisplayName("관리자는 진행 중인 예약만 방문 완료로 바꿀 수 있다")
	void updatesStatusByAdmin() {
		ReservationResponseDto reservation = reservationService.createReservation(userId, request(10));

		assertThat(reservationService.updateReservationStatus(reservation.reservationId(), ReservationStatus.COMPLETED).status())
				.isEqualTo(ReservationStatus.COMPLETED);
		assertError(() -> reservationService.updateReservationStatus(reservation.reservationId(), ReservationStatus.NO_SHOW),
				ReservationErrorCode.INVALID_STATUS_CHANGE);
		assertThat(reservationService.getReservationList(storeId, visitDate, ReservationStatus.COMPLETED, 0, 20).content())
				.extracting(ReservationResponseDto::reservationId)
				.containsExactly(reservation.reservationId());
	}

	@Test
	@DisplayName("알림을 읽음 처리한다")
	void readsNotification() {
		reservationService.createReservation(userId, request(10));
		Long notificationId = notificationService.getMyNotificationList(userId).get(0).notificationId();

		assertThat(notificationService.readNotification(userId, notificationId).read()).isTrue();
	}

	private ReservationCreateRequestDto request(int hour) {
		return request(visitDate.atTime(hour, 0));
	}

	private ReservationCreateRequestDto request(LocalDateTime visitAt) {
		return new ReservationCreateRequestDto(storeId, ReservationPurpose.PLAN_CHANGE, visitAt);
	}

	private Long user() {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (email, password_hash, name, role) VALUES (?, 'hash', '예약자', 'USER') RETURNING user_id",
				Long.class, UUID.randomUUID() + "@test.com");
	}

	private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ReservationErrorCode errorCode) {
		assertThatThrownBy(call)
				.isInstanceOfSatisfying(ReservationException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
	}
}
