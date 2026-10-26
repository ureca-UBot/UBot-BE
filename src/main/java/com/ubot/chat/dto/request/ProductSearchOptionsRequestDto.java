package com.ubot.chat.dto.request;

import com.ubot.ai.tool.product.ProductType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

// 단계별 조건 선택에서 key 단계의 선택지마다 결과가 있는지 묻습니다.
// keyword와 conditions는 ProductSearchRequestDto와 같고, conditions에는 앞 단계까지 고른 값만 넣습니다.
public record ProductSearchOptionsRequestDto(
        @NotNull ProductType type,
        @Size(max = 50) String keyword,
        @Size(max = 10) Map<String, @Size(max = 50) String> conditions,
        @NotBlank @Size(max = 30) String key) {
}
