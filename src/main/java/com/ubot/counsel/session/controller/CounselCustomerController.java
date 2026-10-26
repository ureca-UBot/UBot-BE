package com.ubot.counsel.session.controller;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.session.dto.response.CustomerInfoResponseDto;
import com.ubot.counsel.session.service.CounselCustomerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/counsel/sessions")
public class CounselCustomerController {
    private final CounselCustomerService service;
    @GetMapping("/{id}/customer")
    public ApiResponse<CustomerInfoResponseDto> customer(@AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.success(service.getCustomer(Caller.from(principal), id));
    }
}
