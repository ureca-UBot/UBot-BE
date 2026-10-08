package com.ubot.addon.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.addon.dto.request.AdminAddonServiceCreateRequestDto;
import com.ubot.addon.dto.request.AdminAddonServiceUpdateRequestDto;
import com.ubot.addon.dto.response.AdminAddonServiceResponseDto;
import com.ubot.addon.entity.AddonService;
import com.ubot.addon.exception.AddonServiceErrorCode;
import com.ubot.addon.exception.AddonServiceException;
import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminAddonService {

    private final AddonServiceRepository productRepository;
    private final UserRepository userRepository;

    public AdminAddonServiceResponseDto createAddonService(Long administratorId, AdminAddonServiceCreateRequestDto request) {
        if (productRepository.existsByCode(request.code())) {
            throw new AddonServiceException(AddonServiceErrorCode.DUPLICATE_CODE);
        }
        AddonService product = AddonService.create(
                userRepository.getReferenceById(administratorId),
                request.code(),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.monthlyFee()
        );
        AddonService savedProduct = ProductWriteSupport.saveUnique(
                () -> productRepository.saveAndFlush(product),
                () -> new AddonServiceException(AddonServiceErrorCode.DUPLICATE_CODE)
        );
        log.info("부가서비스 상품을 등록했습니다: 상품ID={}", savedProduct.getAddonServiceId());
        return AdminAddonServiceResponseDto.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminAddonServiceResponseDto> getAddonServiceList(
            String keyword, MasterStatus status, Boolean deleted, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, status, deleted),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "addonServiceId"))
        ).map(AdminAddonServiceResponseDto::from));
    }

    @Transactional(readOnly = true)
    public AdminAddonServiceResponseDto getAddonService(Long addonServiceId) {
        return AdminAddonServiceResponseDto.from(productRepository.findById(addonServiceId)
                .orElseThrow(() -> new AddonServiceException(AddonServiceErrorCode.PRODUCT_NOT_FOUND)));
    }

    public AdminAddonServiceResponseDto updateAddonService(
            Long administratorId, Long addonServiceId, AdminAddonServiceUpdateRequestDto request
    ) {
        AddonService product = getUndeletedProduct(addonServiceId);
        product.update(
                userRepository.getReferenceById(administratorId),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.monthlyFee()
        );
        log.info("부가서비스 상품을 수정했습니다: 상품ID={}", addonServiceId);
        return AdminAddonServiceResponseDto.from(product);
    }

    public AdminAddonServiceResponseDto changeStatus(
            Long administratorId, Long addonServiceId, ProductStatusUpdateRequestDto request
    ) {
        AddonService product = getUndeletedProduct(addonServiceId);
        product.changeStatus(userRepository.getReferenceById(administratorId), request.status());
        return AdminAddonServiceResponseDto.from(product);
    }

    public void deleteAddonService(Long administratorId, Long addonServiceId) {
        getUndeletedProduct(addonServiceId).delete(userRepository.getReferenceById(administratorId));
        log.info("부가서비스 상품을 삭제했습니다: 상품ID={}", addonServiceId);
    }

    private AddonService getUndeletedProduct(Long addonServiceId) {
        return productRepository.findByIdForUpdate(addonServiceId)
                .orElseThrow(() -> new AddonServiceException(AddonServiceErrorCode.PRODUCT_NOT_FOUND));
    }

    private String normalizeDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
