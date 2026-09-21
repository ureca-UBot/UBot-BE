package com.ubot.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.store.dto.request.AdminStoreCreateRequestDto;
import com.ubot.store.dto.response.AdminStoreResponseDto;
import com.ubot.store.exception.DuplicateStoreException;
import com.ubot.store.exception.InvalidStoreCoordinatesException;
import com.ubot.store.service.AdminStoreService;

@WebMvcTest(AdminStoreController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("관리자 매장 Controller 테스트")
class AdminStoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminStoreService adminStoreService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("매장을 등록하면 201 응답과 생성된 자원 위치를 반환한다")
    void createsStore() throws Exception {
        when(adminStoreService.createStore(any(AdminStoreCreateRequestDto.class)))
                .thenReturn(response());

        mockMvc.perform(post("/admin/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeName": "신규 매장",
                                  "sido": "서울특별시",
                                  "sigungu": "강남구",
                                  "address": "서울특별시 강남구 테스트로 1",
                                  "latitude": 37.5000000,
                                  "longitude": 127.0000000,
                                  "serviceCodes": ["APPLE_AS"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/stores/6"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.storeId").value(6));
    }

    @Test
    @DisplayName("활성 상태인 동일 매장을 다시 등록하면 409 응답을 반환한다")
    void rejectsDuplicateStore() throws Exception {
        when(adminStoreService.createStore(any(AdminStoreCreateRequestDto.class)))
                .thenThrow(new DuplicateStoreException());

        mockMvc.perform(post("/admin/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeName": "강남역점",
                                  "address": "서울특별시 강남구 강남대로 396",
                                  "latitude": 37.4987000,
                                  "longitude": 127.0286000
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DUPLICATE_STORE"))
                .andExpect(jsonPath("$.message").value("이미 등록된 매장입니다."));
    }

    @Test
    @DisplayName("등록 좌표가 허용 범위를 벗어나면 400 응답을 반환한다")
    void rejectsCoordinatesOutsideRange() throws Exception {
        mockMvc.perform(post("/admin/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeName": "신규 매장",
                                  "address": "서울특별시 강남구 테스트로 1",
                                  "latitude": 40,
                                  "longitude": 133
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(adminStoreService);
    }

    @Test
    @DisplayName("수정 좌표가 대한민국 범위를 벗어나면 400 응답을 반환한다")
    void rejectsPatchCoordinatesOutsideKorea() throws Exception {
        mockMvc.perform(patch("/admin/stores/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "latitude": 32,
                                  "longitude": 123
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(adminStoreService);
    }

    @Test
    @DisplayName("빈 서비스 코드를 전달하면 400 응답을 반환한다")
    void rejectsBlankServiceCode() throws Exception {
        mockMvc.perform(post("/admin/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeName": "신규 매장",
                                  "address": "서울특별시 강남구 테스트로 1",
                                  "latitude": 37.5,
                                  "longitude": 127.0,
                                  "serviceCodes": ["   "]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(adminStoreService);
    }

    @Test
    @DisplayName("PATCH에서 좌표 한쪽만 전달하면 400 응답을 반환한다")
    void rejectsIncompleteCoordinatePair() throws Exception {
        when(adminStoreService.updateStore(any(), any()))
                .thenThrow(new InvalidStoreCoordinatesException());

        mockMvc.perform(patch("/admin/stores/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 37.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STORE_COORDINATES"));
    }

    @Test
    @DisplayName("PATCH에서 빈 문자열을 전달하면 400 응답을 반환한다")
    void rejectsBlankPatchValue() throws Exception {
        mockMvc.perform(patch("/admin/stores/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"address": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("매장을 삭제하면 204 응답을 반환한다")
    void deletesStore() throws Exception {
        mockMvc.perform(delete("/admin/stores/1"))
                .andExpect(status().isNoContent());
    }

    private AdminStoreResponseDto response() {
        LocalDateTime now = LocalDateTime.now();
        return new AdminStoreResponseDto(
                6L,
                "신규 매장",
                "서울특별시",
                "강남구",
                "서울특별시 강남구 테스트로 1",
                new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000"),
                null,
                null,
                true,
                now,
                now,
                List.of(new AdminStoreResponseDto.ServiceResponse("APPLE_AS", "애플 A/S"))
        );
    }
}
