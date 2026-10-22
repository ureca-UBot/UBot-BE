package com.ubot.plan.service;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.addon.dto.response.AddonServiceResponseDto;
import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.common.repository.ProductSubscriptionRepository;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.plan.dto.request.AdminPlanCreateRequestDto;
import com.ubot.plan.dto.request.AdminPlanIncludedAddonRequestDto;
import com.ubot.plan.dto.request.AdminPlanUpdateRequestDto;
import com.ubot.plan.dto.request.AdminPlanStatusUpdateRequestDto;
import com.ubot.plan.dto.response.AdminPlanResponseDto;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.exception.PlanErrorCode;
import com.ubot.plan.exception.PlanException;
import com.ubot.plan.repository.PlanIncludedAddonRepository;
import com.ubot.plan.repository.PlanRepository;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminPlanService {

    private final PlanRepository productRepository;
    private final UserRepository userRepository;
    private final ProductSubscriptionRepository subscriptionRepository;
    private final PlanIncludedAddonRepository includedAddonRepository;
    private final AddonServiceRepository addonServiceRepository;
    private final PlanService planService;

    public AdminPlanResponseDto createPlan(Long administratorId, AdminPlanCreateRequestDto request) {
        validatePlan(request.dataAmountMb(), request.dataUnlimited(), request.exhaustedSpeedKbps(), request.voiceMinutes(), request.voiceUnlimited(), request.smsCount(), request.smsUnlimited(), request.tetheringAmountMb(), request.minAge(), request.maxAge());
        if (productRepository.existsByPlanCode(request.planCode())) {
            throw new PlanException(PlanErrorCode.DUPLICATE_CODE);
        }
        Plan product = Plan.create(
                userRepository.getReferenceById(administratorId),
                request.planCode(),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.networkType(),
                request.targetGroup(),
                request.monthlyFee(),
                request.dataAmountMb(),
                request.dataUnlimited(),
                request.exhaustedSpeedKbps(),
                request.voiceMinutes(),
                request.voiceUnlimited(),
                request.smsCount(),
                request.smsUnlimited(),
                request.tetheringAmountMb(),
                request.minAge(),
                request.maxAge()
        );
        Plan savedProduct = ProductWriteSupport.saveUnique(
                () -> productRepository.saveAndFlush(product),
                () -> new PlanException(PlanErrorCode.DUPLICATE_CODE)
        );
        log.info("요금제를 등록했습니다: 상품ID={}", savedProduct.getPlanId());
        return AdminPlanResponseDto.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminPlanResponseDto> getPlanList(
            String keyword, MasterStatus status, Boolean deleted, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, status, deleted),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "planId"))
        ).map(AdminPlanResponseDto::from));
    }

    @Transactional(readOnly = true)
    public AdminPlanResponseDto getPlan(Long planId) {
        return AdminPlanResponseDto.from(productRepository.findById(planId)
                .orElseThrow(() -> new PlanException(PlanErrorCode.PRODUCT_NOT_FOUND)));
    }

    public AdminPlanResponseDto updatePlan(
            Long administratorId, Long planId, AdminPlanUpdateRequestDto request
    ) {
        validatePlan(request.dataAmountMb(), request.dataUnlimited(), request.exhaustedSpeedKbps(), request.voiceMinutes(), request.voiceUnlimited(), request.smsCount(), request.smsUnlimited(), request.tetheringAmountMb(), request.minAge(), request.maxAge());
        Plan product = getUndeletedProduct(planId);
        if (changesConditions(product, request) && subscriptionRepository.existsByPlanId(planId)) {
            throw new PlanException(PlanErrorCode.PRODUCT_IN_USE);
        }
        product.update(
                userRepository.getReferenceById(administratorId),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.networkType(),
                request.targetGroup(),
                request.monthlyFee(),
                request.dataAmountMb(),
                request.dataUnlimited(),
                request.exhaustedSpeedKbps(),
                request.voiceMinutes(),
                request.voiceUnlimited(),
                request.smsCount(),
                request.smsUnlimited(),
                request.tetheringAmountMb(),
                request.minAge(),
                request.maxAge()
        );
        log.info("요금제를 수정했습니다: 상품ID={}", planId);
        return AdminPlanResponseDto.from(product);
    }

    public AdminPlanResponseDto changeStatus(
            Long administratorId, Long planId, AdminPlanStatusUpdateRequestDto request
    ) {
        Plan product = getUndeletedProduct(planId);
        product.changeStatus(userRepository.getReferenceById(administratorId), request.status());
        return AdminPlanResponseDto.from(product);
    }

    public void deletePlan(Long administratorId, Long planId) {
        Plan product = getUndeletedProduct(planId);
        if (subscriptionRepository.existsByPlanId(planId)) {
            throw new PlanException(PlanErrorCode.PRODUCT_IN_USE);
        }
        product.delete(userRepository.getReferenceById(administratorId));
        log.info("요금제를 삭제했습니다: 상품ID={}", planId);
    }

    // 중복 ID는 한 번만 넣고, 삭제된 부가서비스나 없는 ID가 있으면 바꾸지 않는다.
    public List<AddonServiceResponseDto> replaceIncludedAddons(
            Long administratorId, Long planId, AdminPlanIncludedAddonRequestDto request
    ) {
        Plan plan = getUndeletedProduct(planId);
        List<Long> addonServiceIds = request.addonServiceIds().stream().distinct().toList();
        long undeleted = addonServiceRepository.findAllById(addonServiceIds).stream()
                .filter(addon -> addon.getDeletedAt() == null)
                .count();
        if (undeleted != addonServiceIds.size()) {
            throw new PlanException(PlanErrorCode.INVALID_PRODUCT);
        }
        includedAddonRepository.replace(planId, addonServiceIds);
        plan.markIncludedAddonsUpdated(userRepository.getReferenceById(administratorId));
        log.info("요금제 포함 부가서비스를 바꿨습니다: 상품ID={}, 부가서비스 수={}", planId, addonServiceIds.size());
        return planService.getIncludedAddonListIgnoringStatus(planId);
    }

    private Plan getUndeletedProduct(Long planId) {
        return productRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new PlanException(PlanErrorCode.PRODUCT_NOT_FOUND));
    }

    private String normalizeDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean changesConditions(Plan product, AdminPlanUpdateRequestDto request) {
        return !Objects.equals(product.getDescription(), normalizeDescription(request.description()))
                || !Objects.equals(product.getNetworkType(), request.networkType())
                || !Objects.equals(product.getTargetGroup(), request.targetGroup())
                || !Objects.equals(product.getMonthlyFee(), request.monthlyFee())
                || !Objects.equals(product.getDataAmountMb(), request.dataAmountMb())
                || !Objects.equals(product.isDataUnlimited(), request.dataUnlimited())
                || !Objects.equals(product.getExhaustedSpeedKbps(), request.exhaustedSpeedKbps())
                || !Objects.equals(product.getVoiceMinutes(), request.voiceMinutes())
                || !Objects.equals(product.isVoiceUnlimited(), request.voiceUnlimited())
                || !Objects.equals(product.getSmsCount(), request.smsCount())
                || !Objects.equals(product.isSmsUnlimited(), request.smsUnlimited())
                || !Objects.equals(product.getTetheringAmountMb(), request.tetheringAmountMb())
                || !Objects.equals(product.getMinAge(), request.minAge())
                || !Objects.equals(product.getMaxAge(), request.maxAge());
    }

    private void validatePlan(
            Long dataAmountMb, Boolean dataUnlimited, Integer exhaustedSpeedKbps,
            Integer voiceMinutes, Boolean voiceUnlimited, Integer smsCount, Boolean smsUnlimited,
            Long tetheringAmountMb, Integer minAge, Integer maxAge
    ) {
        if (dataUnlimited == null || voiceUnlimited == null || smsUnlimited == null || tetheringAmountMb == null
                || (dataUnlimited ? dataAmountMb != null || exhaustedSpeedKbps != null : dataAmountMb == null)
                || (voiceUnlimited ? voiceMinutes != null : voiceMinutes == null)
                || (smsUnlimited ? smsCount != null : smsCount == null)
                || (!dataUnlimited && tetheringAmountMb > dataAmountMb)
                || (minAge != null && maxAge != null && minAge > maxAge)) {
            throw new PlanException(PlanErrorCode.INVALID_PRODUCT);
        }
    }
}
