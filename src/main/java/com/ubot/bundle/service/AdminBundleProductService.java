package com.ubot.bundle.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.bundle.dto.request.AdminBundleProductCreateRequestDto;
import com.ubot.bundle.dto.request.AdminBundleProductUpdateRequestDto;
import com.ubot.bundle.dto.response.AdminBundleProductResponseDto;
import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.exception.BundleProductErrorCode;
import com.ubot.bundle.exception.BundleProductException;
import com.ubot.bundle.repository.BundleProductRepository;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminBundleProductService {

    private final BundleProductRepository productRepository;
    private final UserRepository userRepository;

    public AdminBundleProductResponseDto createBundleProduct(Long administratorId, AdminBundleProductCreateRequestDto request) {
        if (productRepository.existsByCode(request.code())) {
            throw new BundleProductException(BundleProductErrorCode.DUPLICATE_CODE);
        }
        BundleProduct product = BundleProduct.create(
                userRepository.getReferenceById(administratorId),
                request.code(),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.discountAmount()
        );
        BundleProduct savedProduct = ProductWriteSupport.saveUnique(
                () -> productRepository.saveAndFlush(product),
                () -> new BundleProductException(BundleProductErrorCode.DUPLICATE_CODE)
        );
        log.info("결합 상품을 등록했습니다: 상품ID={}", savedProduct.getBundleProductId());
        return AdminBundleProductResponseDto.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminBundleProductResponseDto> getBundleProductList(
            String keyword, MasterStatus status, Boolean deleted, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, status, deleted),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "bundleProductId"))
        ).map(AdminBundleProductResponseDto::from));
    }

    @Transactional(readOnly = true)
    public AdminBundleProductResponseDto getBundleProduct(Long bundleProductId) {
        return AdminBundleProductResponseDto.from(productRepository.findById(bundleProductId)
                .orElseThrow(() -> new BundleProductException(BundleProductErrorCode.PRODUCT_NOT_FOUND)));
    }

    public AdminBundleProductResponseDto updateBundleProduct(
            Long administratorId, Long bundleProductId, AdminBundleProductUpdateRequestDto request
    ) {
        BundleProduct product = getUndeletedProduct(bundleProductId);
        product.update(
                userRepository.getReferenceById(administratorId),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.discountAmount()
        );
        log.info("결합 상품을 수정했습니다: 상품ID={}", bundleProductId);
        return AdminBundleProductResponseDto.from(product);
    }

    public AdminBundleProductResponseDto changeStatus(
            Long administratorId, Long bundleProductId, ProductStatusUpdateRequestDto request
    ) {
        BundleProduct product = getUndeletedProduct(bundleProductId);
        product.changeStatus(userRepository.getReferenceById(administratorId), request.status());
        return AdminBundleProductResponseDto.from(product);
    }

    public void deleteBundleProduct(Long administratorId, Long bundleProductId) {
        getUndeletedProduct(bundleProductId).delete(userRepository.getReferenceById(administratorId));
        log.info("결합 상품을 삭제했습니다: 상품ID={}", bundleProductId);
    }

    private BundleProduct getUndeletedProduct(Long bundleProductId) {
        return productRepository.findByIdForUpdate(bundleProductId)
                .orElseThrow(() -> new BundleProductException(BundleProductErrorCode.PRODUCT_NOT_FOUND));
    }

    private String normalizeDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
