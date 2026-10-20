package com.ubot.inquiry.dto.response;

import com.ubot.inquiry.enums.InquiryStatus;
import com.ubot.inquiry.entity.Inquiry;

import java.time.LocalDateTime;

public record InquiryResponse(
        Long id,
        String title,
        String content,
        InquiryStatus status,
        String adminReply,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime resolvedAt) {
    public static InquiryResponse from(Inquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getTitle(), inquiry.getContent(),
                inquiry.getStatus(), inquiry.getAdminReply(), inquiry.getCreatedAt(),
                inquiry.getUpdatedAt(), inquiry.getResolvedAt());
    }
}
