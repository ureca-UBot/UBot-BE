package com.ubot.store.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.common.PageResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.service.StoreService;

@WebMvcTest(StoreController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("매장 Controller 테스트")
class StoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("페이지 번호가 음수이면 400 응답을 반환한다")
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/stores").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("페이지 크기가 허용 범위를 벗어나면 400 응답을 반환한다")
    void rejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/stores").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("서비스 유형 형식이 잘못되면 400 응답을 반환한다")
    void rejectsInvalidServiceTypeFormat() throws Exception {
        mockMvc.perform(get("/stores").param("type", "apple-as"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("서비스 유형이 10개를 초과하면 400 응답을 반환한다")
    void rejectsTooManyServiceTypes() throws Exception {
        mockMvc.perform(get("/stores").param("type",
                        "TYPE_01", "TYPE_02", "TYPE_03", "TYPE_04", "TYPE_05", "TYPE_06",
                        "TYPE_07", "TYPE_08", "TYPE_09", "TYPE_10", "TYPE_11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("서비스 유형의 앞뒤 공백은 Service에서 정규화할 수 있도록 허용한다")
    void acceptsWhitespaceAroundServiceType() throws Exception {
        when(storeService.getStoreList(null, null, List.of("APPLE_AS"), 0, 20))
                .thenReturn(PageResponseDto.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/stores").param("type", " APPLE_AS "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(storeService).getStoreList(null, null, List.of("APPLE_AS"), 0, 20);
    }

    @Test
    @DisplayName("대한민국 범위를 벗어난 좌표이면 400 응답을 반환한다")
    void rejectsCoordinatesOutsideKorea() throws Exception {
        mockMvc.perform(get("/stores/nearby")
                        .param("latitude", "40.0")
                        .param("longitude", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("클러스터 지도 레벨이 허용 범위를 벗어나면 400 응답을 반환한다")
    void rejectsInvalidClusterLevel() throws Exception {
        mockMvc.perform(get("/stores/map/clusters")
                        .param("swLat", "37.0")
                        .param("swLng", "126.0")
                        .param("neLat", "38.0")
                        .param("neLng", "128.0")
                        .param("level", "14"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("남서 좌표가 북동 좌표보다 크면 표준 400 응답을 반환한다")
    void returnsStandardErrorForInvalidMapBounds() throws Exception {
        when(storeService.getMapStoreList(38.0, 126.0, 37.0, 128.0, null))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/stores/map")
                        .param("swLat", "38.0")
                        .param("swLng", "126.0")
                        .param("neLat", "37.0")
                        .param("neLng", "128.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"));
    }
}
