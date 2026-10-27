package com.ubot.reservation.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.reservation.dto.request.ReservationCreateRequestDto;
import com.ubot.reservation.dto.response.ReservationResponseDto;
import com.ubot.reservation.dto.response.ReservationSlotResponseDto;
import com.ubot.reservation.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
public class ReservationController {
	private final ReservationService reservationService;

	@GetMapping("/stores/{storeId}/reservation-slots")
	public ApiResponse<List<ReservationSlotResponseDto>> getReservationSlotList(
			@Positive @PathVariable("storeId") Long storeId,
			@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
	){
		return ApiResponse.success(reservationService.getReservationSlotList(storeId, date));
	}

	@PostMapping("/reservations")
	public ApiResponse<ReservationResponseDto> createReservation(
			@Valid @RequestBody ReservationCreateRequestDto requestDto,
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(reservationService.createReservation(userDetails.getUserId(), requestDto));
	}

	@GetMapping("/reservations/me")
	public ApiResponse<List<ReservationResponseDto>> getMyReservationList(
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(reservationService.getMyReservationList(userDetails.getUserId()));
	}

	@PatchMapping("/reservations/{reservationId}/cancel")
	public ApiResponse<ReservationResponseDto> cancelReservation(
			@Positive @PathVariable("reservationId") Long reservationId,
			@AuthenticationPrincipal CustomUserDetails userDetails
	){
		return ApiResponse.success(reservationService.cancelReservation(userDetails.getUserId(), reservationId));
	}
}
