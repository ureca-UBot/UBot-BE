package com.ubot.store.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.common.PageResponseDto;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
import com.ubot.direction.service.DirectionsService;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.service.StoreService;

@WebMvcTest(StoreController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("매장 Controller 테스트")
class StoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private DirectionsService directionsService;

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
        when(storeService.getStoreList(null, null, List.of("APPLE_AS"), null, null, 0, 20))
                .thenReturn(PageResponseDto.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/stores").param("type", " APPLE_AS "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(storeService).getStoreList(null, null, List.of("APPLE_AS"), null, null, 0, 20);
    }

    @Test
    @DisplayName("매장 목록은 현재 위치를 함께 받아 매장별 직선거리를 응답한다")
    void passesOriginToStoreList() throws Exception {
        StoreListResponseDto store = new StoreListResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.498, 127.028, 1.234
        );
        when(storeService.getStoreList(null, null, null, 37.5, 127.0, 0, 20))
                .thenReturn(PageResponseDto.of(List.of(store), 0, 20, 1));

        mockMvc.perform(get("/stores")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].distanceKm").value(1.234));
    }

    @Test
    @DisplayName("매장 목록의 현재 위치가 대한민국 범위를 벗어나면 400 응답을 반환한다")
    void rejectsStoreListOriginOutsideKorea() throws Exception {
        mockMvc.perform(get("/stores")
                        .param("latitude", "40.0")
                        .param("longitude", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(storeService);
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

    @Test
    @DisplayName("길찾기는 이동수단, 현재 위치, 매장 ID로 경로를 조회한다")
    void returnsDirections() throws Exception {
        when(directionsService.getStoreDirections(1L, DirectionsMode.WALK, 37.5, 127.0))
                .thenReturn(List.of(new DirectionsResponseDto(
                        DirectionsMode.WALK, 1200, 900,
                        List.of(new PointDto(37.5, 127.0), new PointDto(37.498, 127.028)),
                        List.of()
                )));

        mockMvc.perform(get("/stores/1/directions")
                        .param("mode", "WALK")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].mode").value("WALK"))
                .andExpect(jsonPath("$.data[0].distanceMeters").value(1200))
                .andExpect(jsonPath("$.data[0].durationSeconds").value(900))
                .andExpect(jsonPath("$.data[0].path[1].latitude").value(37.498))
                .andExpect(jsonPath("$.data[0].path[1].longitude").value(127.028));
    }

    @Test
    @DisplayName("대중교통 길찾기는 후보 경로 목록을 그대로 반환한다")
    void returnsMultipleTransitCandidates() throws Exception {
        when(directionsService.getStoreDirections(1L, DirectionsMode.TRANSIT, 37.5, 127.0))
                .thenReturn(List.of(
                        new DirectionsResponseDto(DirectionsMode.TRANSIT, 1000, 800, List.of(), List.of()),
                        new DirectionsResponseDto(DirectionsMode.TRANSIT, 1500, 700, List.of(), List.of())
                ));

        mockMvc.perform(get("/stores/1/directions")
                        .param("mode", "TRANSIT")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].distanceMeters").value(1000))
                .andExpect(jsonPath("$.data[1].distanceMeters").value(1500));
    }

    @Test
    @DisplayName("대중교통 후보 상세 조회는 body의 후보를 그대로 서비스에 넘기고 도보가 채워진 결과를 반환한다")
    void returnsTransitDetailWithFilledWalkingLegs() throws Exception {
        DirectionsResponseDto candidate = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 1000, 800, List.of(), List.of()
        );
        DirectionsResponseDto filled = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 1200, 950, List.of(new PointDto(37.5, 127.0)), List.of()
        );
        when(directionsService.fillTransitWalkingLegs(1L, candidate, 37.5, 127.0)).thenReturn(filled);

        mockMvc.perform(post("/stores/1/directions/transit-detail")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.distanceMeters").value(1200))
                .andExpect(jsonPath("$.data.durationSeconds").value(950));
    }

    @Test
    @DisplayName("길찾기 이동수단이 올바르지 않으면 400 응답을 반환한다")
    void rejectsUnknownDirectionsMode() throws Exception {
        mockMvc.perform(get("/stores/1/directions")
                        .param("mode", "BICYCLE")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(directionsService);
    }

    @Test
    @DisplayName("길찾기의 현재 위치가 대한민국 범위를 벗어나면 400 응답을 반환한다")
    void rejectsDirectionsOriginOutsideKorea() throws Exception {
        mockMvc.perform(get("/stores/1/directions")
                        .param("mode", "CAR")
                        .param("latitude", "40.0")
                        .param("longitude", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(directionsService);
    }

    @Test
    @DisplayName("매장 상세는 현재 위치를 함께 받아 직선거리를 응답한다")
    void passesOriginToStoreDetail() throws Exception {
        when(storeService.getStore(1L, 37.5, 127.0))
                .thenReturn(new StoreDetailResponseDto(
                        1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                        "02-0000-0000", null, 37.498, 127.028, 1.234, List.of()
                ));

        mockMvc.perform(get("/stores/1")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.distanceKm").value(1.234));
    }
}
