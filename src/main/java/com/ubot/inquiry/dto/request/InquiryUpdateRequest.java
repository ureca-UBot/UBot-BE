package com.ubot.inquiry.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InquiryUpdateRequest(
        @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*", message = "제목은 공백일 수 없습니다.") String title,
        @Size(max = 1000) @Pattern(regexp = "(?s).*\\S.*", message = "내용은 공백일 수 없습니다.") String content) {
    public InquiryUpdateRequest {
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
    }
}
