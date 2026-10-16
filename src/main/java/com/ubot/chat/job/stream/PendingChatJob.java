package com.ubot.chat.job.stream;

// deliveryCount: Worker에 전달된 횟수
public record PendingChatJob(String messageId, String consumer, long deliveryCount) {
}
