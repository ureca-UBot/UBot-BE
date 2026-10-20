package com.ubot.inquiry.dto.response;

import com.ubot.inquiry.entity.Inquiry;

public record AdminInquiryResponse(InquiryResponse inquiry, Long userId, String userName,
								   Long resolvedById) {
    public static AdminInquiryResponse from(Inquiry inquiry) {
        return new AdminInquiryResponse(InquiryResponse.from(inquiry), inquiry.getUser().getId(),
                inquiry.getUser().getName(),
                inquiry.getResolvedBy() == null ? null : inquiry.getResolvedBy().getId());
    }
}
