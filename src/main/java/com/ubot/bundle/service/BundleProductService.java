package com.ubot.bundle.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.bundle.dto.response.BundleProductResponseDto;
import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.exception.BundleProductErrorCode;
import com.ubot.bundle.exception.BundleProductException;
import com.ubot.bundle.repository.BundleProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BundleProductService {

    private final BundleProductRepository productRepository;

    public PageResponseDto<BundleProductResponseDto> getBundleProductList(
            String keyword, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, MasterStatus.ACTIVE, false),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "bundleProductId"))
        ).map(BundleProductResponseDto::from));
    }

    public BundleProductResponseDto getBundleProduct(Long bundleProductId) {
        return BundleProductResponseDto.from(productRepository
                .findByBundleProductIdAndStatusAndDeletedAtIsNull(bundleProductId, MasterStatus.ACTIVE)
                .orElseThrow(() -> new BundleProductException(BundleProductErrorCode.PRODUCT_NOT_FOUND)));
    }
}
