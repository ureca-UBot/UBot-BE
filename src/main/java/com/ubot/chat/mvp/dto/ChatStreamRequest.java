package com.ubot.chat.mvp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @NotBlank(message = "질문을 입력해주세요.")
        @Size(max = 4000, message = "질문은 4000자 이내로 입력해주세요.")
        String question) {}
