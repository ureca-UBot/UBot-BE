package com.ubot.chat.dto.response;

public record ChatResponseDto(String answer, boolean success) {

    public static ChatResponseDto createSuccessAnswer(String answer) {
        return new ChatResponseDto(answer, true);
    }

    public static ChatResponseDto createFailureAnswer() {
        return new ChatResponseDto("답변 실패", false);
    }

}
