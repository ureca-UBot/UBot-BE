package com.ubot.faq.publicapi;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/public/faqs")
public class PublicFaqController {
    private final PublicFaqService service;
    @GetMapping("/categories")
    public ApiResponse<List<PublicFaqResponse.Category>> categories() { return ApiResponse.success(service.categories()); }
    @GetMapping
    public ApiResponse<PageResponseDto<PublicFaqResponse.Summary>> list(
        @RequestParam(required = false) String category,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(service.list(category, page, size));
    }
    @GetMapping("/{id}")
    public ApiResponse<PublicFaqResponse.Detail> detail(@PathVariable @Positive Long id) { return ApiResponse.success(service.detail(id)); }
}
