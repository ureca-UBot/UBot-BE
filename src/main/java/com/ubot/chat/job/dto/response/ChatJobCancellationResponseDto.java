package com.ubot.chat.job.dto.response;

/** cancelled=false이면 이미 완료된 결과를 유지합니다. */
public record ChatJobCancellationResponseDto(Long jobId, boolean cancelled) {
}
