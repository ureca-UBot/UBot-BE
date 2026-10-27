package com.ubot.reservation.service;

import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.service.NotificationService;
import com.ubot.reservation.dto.request.ReservationCreateRequestDto;
import com.ubot.reservation.dto.response.ReservationResponseDto;
import com.ubot.reservation.dto.response.ReservationSlotResponseDto;
import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.reservation.exception.ReservationErrorCode;
import com.ubot.reservation.exception.ReservationException;
import com.ubot.reservation.repository.StoreReservationRepository;
import com.ubot.store.entity.Store;
import com.ubot.store.exception.StoreErrorCode;
import com.ubot.store.exception.StoreException;
import com.ubot.store.repository.StoreJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReservationService {
	private static final int MAX_ACTIVE_RESERVATIONS = 3;
	private static final int MAX_DAYS_AHEAD = 14;
	private static final Pattern BUSINESS_HOURS = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*$");
	private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(10, 20, 50);
	private static final Set<ReservationStatus> ADMIN_STATUSES = EnumSet.of(
			ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW, ReservationStatus.CANCELED
	);

	private final StoreReservationRepository storeReservationRepository;
	private final StoreJpaRepository storeJpaRepository;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public List<ReservationSlotResponseDto> getReservationSlotList(Long storeId, LocalDate date){
		Store store = findActiveStore(storeId);
		if(!isReservableDate(date)){
			return List.of();
		}
		Set<LocalDateTime> reserved = new HashSet<>(storeReservationRepository.findVisitAtList(
				storeId, ReservationStatus.RESERVED, date.atStartOfDay(), date.plusDays(1).atStartOfDay()
		));
		LocalDateTime now = LocalDateTime.now();
		return getSlotTimeList(store.getBusinessHours()).stream()
				.map(time -> {
					LocalDateTime visitAt = date.atTime(time);
					return new ReservationSlotResponseDto(time, visitAt.isAfter(now) && !reserved.contains(visitAt));
				})
				.toList();
	}

	@Transactional
	public ReservationResponseDto createReservation(Long userId, ReservationCreateRequestDto requestDto){
		Store store = findActiveStore(requestDto.storeId());
		LocalDateTime visitAt = requestDto.visitAt();
		validateVisitTime(store, visitAt);
		storeReservationRepository.lockUser(userId);
		if(storeReservationRepository.existsByStore_StoreIdAndVisitAtAndStatus(store.getStoreId(), visitAt, ReservationStatus.RESERVED)
				|| storeReservationRepository.existsByUserIdAndVisitAtAndStatus(userId, visitAt, ReservationStatus.RESERVED)){
			throw new ReservationException(ReservationErrorCode.RESERVATION_SLOT_UNAVAILABLE);
		}
		if(storeReservationRepository.countByUserIdAndStatusAndVisitAtAfter(userId, ReservationStatus.RESERVED, LocalDateTime.now())
				>= MAX_ACTIVE_RESERVATIONS){
			throw new ReservationException(ReservationErrorCode.RESERVATION_LIMIT_EXCEEDED);
		}

		StoreReservation reservation;
		try{
			reservation = storeReservationRepository.saveAndFlush(
					StoreReservation.create(userId, store, requestDto.purpose(), visitAt)
			);
		}catch(DataIntegrityViolationException e){
			throw new ReservationException(ReservationErrorCode.RESERVATION_SLOT_UNAVAILABLE);
		}
		notificationService.createReservationNotification(reservation, NotificationType.RESERVATION_CONFIRMED);
		return ReservationResponseDto.from(reservation);
	}

	@Transactional(readOnly = true)
	public List<ReservationResponseDto> getMyReservationList(Long userId){
		return storeReservationRepository.findAllByUserIdOrderByVisitAtDesc(userId).stream()
				.map(ReservationResponseDto::from)
				.toList();
	}

	@Transactional
	public ReservationResponseDto cancelReservation(Long userId, Long reservationId){
		StoreReservation reservation = storeReservationRepository.findByReservationIdAndUserIdForUpdate(reservationId, userId)
				.orElseThrow(() -> new ReservationException(ReservationErrorCode.RESERVATION_NOT_FOUND));
		if(reservation.getStatus() != ReservationStatus.RESERVED || !reservation.getVisitAt().isAfter(LocalDateTime.now())){
			throw new ReservationException(ReservationErrorCode.RESERVATION_NOT_CANCELABLE);
		}
		reservation.updateStatus(ReservationStatus.CANCELED);
		notificationService.createReservationNotification(reservation, NotificationType.RESERVATION_CANCELED);
		return ReservationResponseDto.from(reservation);
	}

	@Transactional(readOnly = true)
	public PageResponseDto<ReservationResponseDto> getReservationList(
			Long storeId,
			LocalDate date,
			ReservationStatus status,
			int page,
			int size
	){
		if(!ALLOWED_PAGE_SIZES.contains(size)){
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		Specification<StoreReservation> spec = (root, query, cb) -> cb.and(
				cb.greaterThanOrEqualTo(root.get("visitAt"), date.atStartOfDay()),
				cb.lessThan(root.get("visitAt"), date.plusDays(1).atStartOfDay())
		);
		if(storeId != null){
			spec = spec.and((root, query, cb) -> cb.equal(root.get("store").get("storeId"), storeId));
		}
		if(status != null){
			spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
		}
		PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.asc("visitAt"), Sort.Order.asc("reservationId")));
		return PageResponseDto.from(storeReservationRepository.findAll(spec, pageRequest).map(ReservationResponseDto::from));
	}

	@Transactional
	public ReservationResponseDto updateReservationStatus(Long reservationId, ReservationStatus status){
		StoreReservation reservation = storeReservationRepository.findByIdForUpdate(reservationId)
				.orElseThrow(() -> new ReservationException(ReservationErrorCode.RESERVATION_NOT_FOUND));
		if(!ADMIN_STATUSES.contains(status) || reservation.getStatus() != ReservationStatus.RESERVED){
			throw new ReservationException(ReservationErrorCode.INVALID_STATUS_CHANGE);
		}
		reservation.updateStatus(status);
		if(status == ReservationStatus.CANCELED){
			notificationService.createReservationNotification(reservation, NotificationType.RESERVATION_CANCELED);
		}
		return ReservationResponseDto.from(reservation);
	}

	private void validateVisitTime(Store store, LocalDateTime visitAt){
		if(!isReservableDate(visitAt.toLocalDate())
				|| !visitAt.isAfter(LocalDateTime.now())
				|| !getSlotTimeList(store.getBusinessHours()).contains(visitAt.toLocalTime())){
			throw new ReservationException(ReservationErrorCode.INVALID_VISIT_TIME);
		}
	}

	private boolean isReservableDate(LocalDate date){
		LocalDate today = LocalDate.now();
		return !date.isBefore(today)
				&& !date.isAfter(today.plusDays(MAX_DAYS_AHEAD))
				&& date.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

	private List<LocalTime> getSlotTimeList(String businessHours){
		if(businessHours == null){
			return List.of();
		}
		Matcher matcher = BUSINESS_HOURS.matcher(businessHours);
		if(!matcher.matches()){
			return List.of();
		}
		int openMinutes = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
		int closeMinutes = Integer.parseInt(matcher.group(3)) * 60 + Integer.parseInt(matcher.group(4));
		List<LocalTime> times = new ArrayList<>();
		for(int minutes = openMinutes; minutes + 60 <= closeMinutes && minutes < 24 * 60; minutes += 60){
			times.add(LocalTime.of(minutes / 60, minutes % 60));
		}
		return times;
	}

	private Store findActiveStore(Long storeId){
		return storeJpaRepository.findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(storeId)
				.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
	}
}
