package com.ubot.reservation.controller;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.reservation.dto.request.ReservationStatusUpdateRequestDto;
import com.ubot.reservation.dto.response.ReservationResponseDto;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.reservation.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/admin/reservations")
@RequiredArgsConstructor
@Validated
public class AdminReservationController {
	private final ReservationService reservationService;

	@GetMapping
	public ApiResponse<PageResponseDto<ReservationResponseDto>> getReservationList(
			@RequestParam(name = "storeId", required = false) @Positive Long storeId,
			@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(name = "status", required = false) ReservationStatus status,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") int size
	){
		LocalDate targetDate = date == null ? LocalDate.now() : date;
		return ApiResponse.success(reservationService.getReservationList(storeId, targetDate, status, page, size));
	}

	@PatchMapping("/{reservationId}/status")
	public ApiResponse<ReservationResponseDto> updateReservationStatus(
			@Positive @PathVariable("reservationId") Long reservationId,
			@Valid @RequestBody ReservationStatusUpdateRequestDto requestDto
	){
		return ApiResponse.success(reservationService.updateReservationStatus(reservationId, requestDto.status()));
	}
}
