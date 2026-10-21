package com.ubot.counsel.admin.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.counsel.admin.dto.request.SafetySentenceCreateRequestDto;
import com.ubot.counsel.admin.dto.request.SafetySentenceIndexRequestDto;
import com.ubot.counsel.admin.dto.request.SafetySentenceUpdateRequestDto;
import com.ubot.counsel.admin.dto.response.SafetySentenceResponseDto;
import com.ubot.counsel.admin.dto.response.SafetySentencesResponseDto;
import com.ubot.counsel.admin.service.AdminSafetySentenceService;
import com.ubot.counsel.auth.model.Caller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/counsel/safety/sentences")
@RequiredArgsConstructor
public class AdminSafetySentenceController {
	private final AdminSafetySentenceService service;
	@GetMapping
	ApiResponse<SafetySentencesResponseDto> getSentences(@AuthenticationPrincipal CustomUserDetails principal,
			@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "0") int page) {
		return ApiResponse.success(service.getSentences(Caller.from(principal), keyword, page));
	}
	@GetMapping("/{sentenceId}")
	ApiResponse<SafetySentenceResponseDto> getSentence(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable long sentenceId) {
		return ApiResponse.success(service.getSentence(Caller.from(principal), sentenceId));
	}
	@PostMapping
	ApiResponse<SafetySentenceResponseDto> createSentence(@AuthenticationPrincipal CustomUserDetails principal,
			@Valid @RequestBody SafetySentenceCreateRequestDto body) {
		return ApiResponse.success(service.createSentence(Caller.from(principal), body));
	}
	@PatchMapping("/{sentenceId}")
	ApiResponse<SafetySentenceResponseDto> updateSentence(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable long sentenceId,
			@Valid @RequestBody SafetySentenceUpdateRequestDto body) {
		return ApiResponse.success(service.updateSentence(Caller.from(principal), sentenceId, body));
	}
	@DeleteMapping("/{sentenceId}")
	ApiResponse<Void> deleteSentence(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable long sentenceId, @RequestParam long revision) {
		service.deleteSentence(Caller.from(principal), sentenceId, revision);
		return ApiResponse.success(null);
	}
	@PostMapping("/{sentenceId}/index")
	ApiResponse<SafetySentenceResponseDto> indexSentence(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable long sentenceId,
			@Valid @RequestBody SafetySentenceIndexRequestDto body) {
		return ApiResponse.success(service.indexSentence(Caller.from(principal), sentenceId, body.revision()));
	}
}
