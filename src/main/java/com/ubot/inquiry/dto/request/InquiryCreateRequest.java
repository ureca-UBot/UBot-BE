package com.ubot.inquiry.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InquiryCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 1000) String content) {
    public InquiryCreateRequest {
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
    }
}
