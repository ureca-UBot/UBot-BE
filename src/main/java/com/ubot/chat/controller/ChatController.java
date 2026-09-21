package com.ubot.chat.controller;

import org.springframework.web.bind.annotation.RestController;

import com.ubot.chat.dto.request.ChatRequestDto;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.service.ChatService;
import com.ubot.common.ApiResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/questions") // 아직 유저 인증 및 세션 고려하지 않음
    public ApiResponse<ChatResponseDto> createChat(@RequestBody ChatRequestDto request) {
        ChatResponseDto response = chatService.createChat(request.question());
        return ApiResponse.success(response);
    }
}
