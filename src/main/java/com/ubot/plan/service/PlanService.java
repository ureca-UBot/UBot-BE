package com.ubot.plan.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.plan.dto.response.PlanSummaryResponseDto;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.exception.PlanErrorCode;
import com.ubot.plan.exception.PlanException;
import com.ubot.plan.repository.PlanRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanService {

    private final PlanRepository productRepository;

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
}
