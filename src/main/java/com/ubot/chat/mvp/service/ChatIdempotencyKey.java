package com.ubot.chat.mvp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** One question's key: authenticated user ID + stored question + its original creation time. */
public final class ChatIdempotencyKey {
    private ChatIdempotencyKey() {}

    public static String create(long userId, String question, LocalDateTime createdAt) {
        if (userId <= 0 || question == null || question.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("사용자 ID, 질문, 생성시간이 필요합니다.");
        }
        // Length-prefix the text so delimiters inside a question cannot change the input fields.
        String source = userId + ":" + question.length() + ":" + question + ":" + createdAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
