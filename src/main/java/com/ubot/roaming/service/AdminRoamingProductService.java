package com.ubot.roaming.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.roaming.dto.request.AdminRoamingProductCreateRequestDto;
import com.ubot.roaming.dto.request.AdminRoamingProductUpdateRequestDto;
import com.ubot.roaming.dto.response.AdminRoamingProductResponseDto;
import com.ubot.roaming.entity.RoamingProduct;
import com.ubot.roaming.exception.RoamingProductErrorCode;
import com.ubot.roaming.exception.RoamingProductException;
import com.ubot.roaming.repository.RoamingProductRepository;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminRoamingProductService {

    private final RoamingProductRepository productRepository;
    private final UserRepository userRepository;

    public AdminRoamingProductResponseDto createRoamingProduct(Long administratorId, AdminRoamingProductCreateRequestDto request) {
        if (productRepository.existsByCode(request.code())) {
            throw new RoamingProductException(RoamingProductErrorCode.DUPLICATE_CODE);
        }
        RoamingProduct product = RoamingProduct.create(
                userRepository.getReferenceById(administratorId),
                request.code(),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.dailyFee(),
                request.dataAmountMb(),
                request.country().trim()
        );
        RoamingProduct savedProduct = ProductWriteSupport.saveUnique(
                () -> productRepository.saveAndFlush(product),
                () -> new RoamingProductException(RoamingProductErrorCode.DUPLICATE_CODE)
        );
        log.info("로밍 상품을 등록했습니다: 상품ID={}", savedProduct.getRoamingProductId());
        return AdminRoamingProductResponseDto.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminRoamingProductResponseDto> getRoamingProductList(
            String keyword, MasterStatus status, Boolean deleted, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, status, deleted),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "roamingProductId"))
        ).map(AdminRoamingProductResponseDto::from));
    }

    @Transactional(readOnly = true)
    public AdminRoamingProductResponseDto getRoamingProduct(Long roamingProductId) {
        return AdminRoamingProductResponseDto.from(productRepository.findById(roamingProductId)
                .orElseThrow(() -> new RoamingProductException(RoamingProductErrorCode.PRODUCT_NOT_FOUND)));
    }

    public AdminRoamingProductResponseDto updateRoamingProduct(
            Long administratorId, Long roamingProductId, AdminRoamingProductUpdateRequestDto request
    ) {
        RoamingProduct product = getUndeletedProduct(roamingProductId);
        product.update(
                userRepository.getReferenceById(administratorId),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.dailyFee(),
                request.dataAmountMb(),
                request.country().trim()
        );
        log.info("로밍 상품을 수정했습니다: 상품ID={}", roamingProductId);
        return AdminRoamingProductResponseDto.from(product);
    }

    public AdminRoamingProductResponseDto changeStatus(
            Long administratorId, Long roamingProductId, ProductStatusUpdateRequestDto request
    ) {
        RoamingProduct product = getUndeletedProduct(roamingProductId);
        product.changeStatus(userRepository.getReferenceById(administratorId), request.status());
        return AdminRoamingProductResponseDto.from(product);
    }

    public void deleteRoamingProduct(Long administratorId, Long roamingProductId) {
        getUndeletedProduct(roamingProductId).delete(userRepository.getReferenceById(administratorId));
        log.info("로밍 상품을 삭제했습니다: 상품ID={}", roamingProductId);
    }

    private RoamingProduct getUndeletedProduct(Long roamingProductId) {
        return productRepository.findByIdForUpdate(roamingProductId)
                .orElseThrow(() -> new RoamingProductException(RoamingProductErrorCode.PRODUCT_NOT_FOUND));
    }

    private String normalizeDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
