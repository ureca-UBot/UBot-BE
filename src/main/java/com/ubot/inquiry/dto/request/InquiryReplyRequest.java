package com.ubot.inquiry.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InquiryReplyRequest(@NotBlank @Size(max = 1000) String reply) {
    public InquiryReplyRequest {
        reply = reply == null ? null : reply.strip();
    }
}
