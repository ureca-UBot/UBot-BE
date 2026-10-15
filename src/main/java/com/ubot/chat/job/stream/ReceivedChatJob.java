package com.ubot.chat.job.stream;

// messageId는 ACK용 Redis 메시지 ID(작업 ID 아님)
public record ReceivedChatJob(String messageId, ChatJobMessage message) {
}
