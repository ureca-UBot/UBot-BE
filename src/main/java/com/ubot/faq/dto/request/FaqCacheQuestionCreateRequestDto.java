package com.ubot.faq.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FaqCacheQuestionCreateRequestDto(
        @NotEmpty @Size(max = 50)
        List<@NotBlank @Size(max = 160) String> questions
) {
}
