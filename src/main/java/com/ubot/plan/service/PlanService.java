package com.ubot.plan.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.addon.dto.response.AddonServiceResponseDto;
import com.ubot.addon.entity.AddonService;
import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.plan.dto.response.PlanSummaryResponseDto;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.exception.PlanErrorCode;
import com.ubot.plan.exception.PlanException;
import com.ubot.plan.repository.PlanIncludedAddonRepository;
import com.ubot.plan.repository.PlanRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanService {

    private final PlanRepository productRepository;
    private final PlanIncludedAddonRepository includedAddonRepository;
    private final AddonServiceRepository addonServiceRepository;

    public PageResponseDto<PlanSummaryResponseDto> getPlanList(
            String keyword, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, MasterStatus.ACTIVE, false),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "planId"))
        ).map(PlanSummaryResponseDto::from));
    }

    public PlanDetailResponseDto getPlan(Long planId) {
        return PlanDetailResponseDto.from(productRepository
                .findByPlanIdAndStatusAndDeletedAtIsNull(planId, MasterStatus.ACTIVE)
                .orElseThrow(() -> new PlanException(PlanErrorCode.PRODUCT_NOT_FOUND)));
    }

    public List<AddonServiceResponseDto> getIncludedAddonList(Long planId) {
        getPlan(planId);
        return getIncludedAddonListIgnoringStatus(planId);
    }

    /** 요금제 상태와 관계없이 포함 부가서비스를 돌려준다. 판매 중지된 요금제의 관리·가입자 조회에 쓴다. */
    public List<AddonServiceResponseDto> getIncludedAddonListIgnoringStatus(Long planId) {
        List<Long> addonServiceIds = includedAddonRepository.findAddonServiceIds(planId);
        Map<Long, AddonService> addons = addonServiceRepository.findAllById(addonServiceIds).stream()
                .collect(Collectors.toMap(AddonService::getAddonServiceId, Function.identity()));
        return addonServiceIds.stream()
                .map(addons::get)
                .filter(Objects::nonNull)
                .map(AddonServiceResponseDto::from)
                .toList();
    }
}
