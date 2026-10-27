package com.ubot.monitoring.operationmonitoring.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.monitoring.operationmonitoring.ServiceMonitoringRange;
import com.ubot.monitoring.operationmonitoring.dto.EndpointMonitoringResponse;
import com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse;
import com.ubot.monitoring.operationmonitoring.service.ServiceOperationMonitoringService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ServiceOperationMonitoringController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("서비스 운영 모니터링 Controller 테스트")
class ServiceOperationMonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceOperationMonitoringService service;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("개요 조회는 범위가 없으면 6시간 범위를 사용한다")
    void getsOverviewWithDefaultRange() throws Exception {
        when(service.getOverview(ServiceMonitoringRange.SIX_HOURS)).thenReturn(overview("6h"));

        mockMvc.perform(get("/admin/operation-monitoring/services/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.window.range").value("6h"));

        verify(service).getOverview(ServiceMonitoringRange.SIX_HOURS);
    }

    @Test
    @DisplayName("엔드포인트 조회는 요청 범위를 Service에 전달한다")
    void getsEndpointsWithRequestedRange() throws Exception {
        when(service.getEndpoints(ServiceMonitoringRange.ONE_HOUR)).thenReturn(endpoints("1h"));

        mockMvc.perform(get("/admin/operation-monitoring/services/endpoints").param("range", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window.range").value("1h"));

        verify(service).getEndpoints(ServiceMonitoringRange.ONE_HOUR);
    }

    @Test
    @DisplayName("허용하지 않은 범위는 400으로 거절한다")
    void rejectsUnsupportedRange() throws Exception {
        mockMvc.perform(get("/admin/operation-monitoring/services/overview").param("range", "7d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-002"));
    }

    private ServiceMonitoringOverviewResponse overview(String range) {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        return new ServiceMonitoringOverviewResponse(
                new ServiceMonitoringOverviewResponse.Window(range, now.minusSeconds(3600), now, 60),
                new ServiceMonitoringOverviewResponse.ServiceStatuses(
                        ServiceMonitoringOverviewResponse.ServiceStatus.UP,
                        ServiceMonitoringOverviewResponse.ServiceStatus.UP,
                        ServiceMonitoringOverviewResponse.ServiceStatus.UP),
                new ServiceMonitoringOverviewResponse.Summary(null, null, null, null,
                        new ServiceMonitoringOverviewResponse.DatabasePool(null, null, null, null)),
                new ServiceMonitoringOverviewResponse.Series(List.of(),
                        new ServiceMonitoringOverviewResponse.ErrorRate(List.of(), List.of()),
                        new ServiceMonitoringOverviewResponse.ApiLatency(List.of(), List.of(), List.of()),
                        new ServiceMonitoringOverviewResponse.Backend(List.of(), List.of(), List.of(), List.of()),
                        new ServiceMonitoringOverviewResponse.DatabasePoolSeries(List.of(), List.of(), List.of()),
                        new ServiceMonitoringOverviewResponse.ContainerResources(java.util.Map.of(), java.util.Map.of()))
        );
    }

    private EndpointMonitoringResponse endpoints(String range) {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        return new EndpointMonitoringResponse(new EndpointMonitoringResponse.Window(range, now.minusSeconds(3600), now),
                List.of());
    }
}
