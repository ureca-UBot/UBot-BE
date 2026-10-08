package com.ubot.addon.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.addon.dto.response.AddonServiceResponseDto;
import com.ubot.addon.entity.AddonService;
import com.ubot.addon.exception.AddonServiceErrorCode;
import com.ubot.addon.exception.AddonServiceException;
import com.ubot.addon.repository.AddonServiceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddonCatalogService {

    private final AddonServiceRepository productRepository;

    public PageResponseDto<AddonServiceResponseDto> getAddonServiceList(
            String keyword, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, MasterStatus.ACTIVE, false),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "addonServiceId"))
        ).map(AddonServiceResponseDto::from));
    }

    public AddonServiceResponseDto getAddonService(Long addonServiceId) {
        return AddonServiceResponseDto.from(productRepository
                .findByAddonServiceIdAndStatusAndDeletedAtIsNull(addonServiceId, MasterStatus.ACTIVE)
                .orElseThrow(() -> new AddonServiceException(AddonServiceErrorCode.PRODUCT_NOT_FOUND)));
    }
}
