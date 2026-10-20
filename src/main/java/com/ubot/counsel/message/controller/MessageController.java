package com.ubot.counsel.message.controller;

import com.ubot.counsel.auth.model.Caller;
import com.ubot.common.ApiResponse;
import com.ubot.counsel.message.dto.request.SendMessageRequestDto;
import com.ubot.counsel.message.dto.response.MessageReceiptResponseDto;
import com.ubot.counsel.message.dto.response.MessageResponseDto;
import com.ubot.counsel.message.service.MessageService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.ubot.auth.config.CustomUserDetails;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/counsel/sessions/{id}/messages")
public class MessageController {
	private final MessageService service;
	public MessageController(MessageService service) {
		this.service = service;
	}
	@PostMapping
	ApiResponse<MessageReceiptResponseDto> sendMessage(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id, @Valid @RequestBody SendMessageRequestDto body) {
		return ApiResponse.success(service.sendMessage(Caller.from(principal), id, body.clientMessageId(), body.content()));
	}
	@GetMapping
	ApiResponse<List<MessageResponseDto>> getMessages(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id,
			@RequestParam(defaultValue="0") long afterSequence, @RequestParam(defaultValue="50") int limit) {
		return ApiResponse.success(service.getMessages(Caller.from(principal), id, afterSequence, limit));
	}
}
