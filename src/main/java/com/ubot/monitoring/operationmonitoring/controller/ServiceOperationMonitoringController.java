package com.ubot.monitoring.operationmonitoring.controller;

import com.ubot.common.ApiResponse;
import com.ubot.monitoring.operationmonitoring.ServiceMonitoringRange;
import com.ubot.monitoring.operationmonitoring.dto.EndpointMonitoringResponse;
import com.ubot.monitoring.operationmonitoring.dto.ServiceMonitoringOverviewResponse;
import com.ubot.monitoring.operationmonitoring.service.ServiceOperationMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/operation-monitoring/services")
public class ServiceOperationMonitoringController {

    private final ServiceOperationMonitoringService serviceOperationMonitoringService;

    @GetMapping("/overview")
    public ApiResponse<ServiceMonitoringOverviewResponse> getOverview(
            @RequestParam(name = "range", defaultValue = "6h") String range
    ) {
        return ApiResponse.success(serviceOperationMonitoringService.getOverview(ServiceMonitoringRange.from(range)));
    }

    @GetMapping("/endpoints")
    public ApiResponse<EndpointMonitoringResponse> getEndpoints(
            @RequestParam(name = "range", defaultValue = "6h") String range
    ) {
        return ApiResponse.success(serviceOperationMonitoringService.getEndpoints(ServiceMonitoringRange.from(range)));
    }
}
