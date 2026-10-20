package com.ubot.inquiry.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.common.PageResponseDto;
import com.ubot.inquiry.dto.request.InquiryCreateRequest;
import com.ubot.inquiry.dto.request.InquiryReplyRequest;
import com.ubot.inquiry.dto.request.InquiryUpdateRequest;
import com.ubot.inquiry.dto.response.AdminInquiryResponse;
import com.ubot.inquiry.dto.response.InquiryResponse;
import com.ubot.inquiry.enums.InquiryStatus;
import com.ubot.inquiry.service.InquiryService;
import com.ubot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@DisplayName("고객문의 API 테스트")
class InquiryControllerTest {
    private static final long USER_ID = 7L;
    private static final long INQUIRY_ID = 42L;
    private final InquiryService inquiryService = mock(InquiryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CustomUserDetails principal = new CustomUserDetails(User.builder().id(USER_ID).build());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InquiryController(inquiryService), new AdminInquiryController(inquiryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CustomUserDetails.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return principal;
                    }
                })
                .build();
    }

    @Test
    @DisplayName("문의 등록은 인증된 회원 ID를 사용하고 ApiResponse로 결과를 반환한다")
    void createReturnsApiResponseAndUsesAuthenticatedUser() throws Exception {
        when(inquiryService.create(eq(USER_ID), any(InquiryCreateRequest.class))).thenReturn(response());

        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"content\":\"내용\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(INQUIRY_ID))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));
    }

    @Test
    @DisplayName("내 문의 조회·수정·삭제는 인증된 회원 ID로 처리한다")
    void myListAndChangesPassAuthenticatedUserId() throws Exception {
        when(inquiryService.getMine(USER_ID, 0, 20))
                .thenReturn(PageResponseDto.of(List.of(response()), 0, 20, 1));
        when(inquiryService.update(eq(USER_ID), eq(INQUIRY_ID), any(InquiryUpdateRequest.class)))
                .thenReturn(response());

        mockMvc.perform(get("/inquiries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(INQUIRY_ID));
        mockMvc.perform(patch("/inquiries/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"수정 제목\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/inquiries/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(inquiryService).getMine(USER_ID, 0, 20);
        verify(inquiryService).update(eq(USER_ID), eq(INQUIRY_ID), any(InquiryUpdateRequest.class));
        verify(inquiryService).delete(USER_ID, INQUIRY_ID);
    }

    @Test
    @DisplayName("공백 입력과 제목 200자·내용 1000자 초과를 거부한다")
    void rejectsBlankAndOverLimitInquiryFields() throws Exception {
        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "가".repeat(201) + "\",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"content\":\"가" + "가".repeat(1000) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/inquiries/42").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자 답변은 관리자 ID로 처리하고 공백·1000자 초과 입력을 거부한다")
    void adminReplyValidatesInputAndUsesAuthenticatedAdmin() throws Exception {
        var adminResponse = new AdminInquiryResponse(response(), USER_ID, "회원", null);
        when(inquiryService.reply(eq(USER_ID), eq(INQUIRY_ID), any(InquiryReplyRequest.class)))
                .thenReturn(adminResponse);

        mockMvc.perform(patch("/admin/inquiries/42/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"답변\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inquiry.id").value(INQUIRY_ID));
        verify(inquiryService).reply(eq(USER_ID), eq(INQUIRY_ID), any(InquiryReplyRequest.class));
        mockMvc.perform(patch("/admin/inquiries/42/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/admin/inquiries/42/reply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private InquiryResponse response() {
        return new InquiryResponse(INQUIRY_ID, "제목", "내용", InquiryStatus.RECEIVED, null,
                LocalDateTime.of(2026, 10, 20, 12, 0), LocalDateTime.of(2026, 10, 20, 12, 0), null);
    }
}
