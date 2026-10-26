package com.ubot.home.service;

import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.common.repository.ProductSubscriptionRepository;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.home.dto.request.AdminHomeProductCreateRequestDto;
import com.ubot.home.dto.request.AdminHomeProductUpdateRequestDto;
import com.ubot.home.dto.response.AdminHomeProductResponseDto;
import com.ubot.home.entity.HomeProduct;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.exception.HomeProductErrorCode;
import com.ubot.home.exception.HomeProductException;
import com.ubot.home.repository.HomeProductRepository;
import com.ubot.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminHomeProductService {

    private final HomeProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductSubscriptionRepository subscriptionRepository;

    public AdminHomeProductResponseDto createHomeProduct(Long administratorId, AdminHomeProductCreateRequestDto request) {
        validateSpeed(request.productType(), request.speedMbps());
        if (productRepository.existsByCode(request.code())) {
            throw new HomeProductException(HomeProductErrorCode.DUPLICATE_CODE);
        }
        HomeProduct product = HomeProduct.create(
                userRepository.getReferenceById(administratorId),
                request.code(),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.productType(),
                request.speedMbps(),
                request.monthlyFee()
        );
        HomeProduct savedProduct = ProductWriteSupport.saveUnique(
                () -> productRepository.saveAndFlush(product),
                () -> new HomeProductException(HomeProductErrorCode.DUPLICATE_CODE)
        );
        log.info("인터넷/IPTV 상품을 등록했습니다: 상품ID={}", savedProduct.getHomeProductId());
        return AdminHomeProductResponseDto.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<AdminHomeProductResponseDto> getHomeProductList(
            String keyword, MasterStatus status, Boolean deleted, int page, int size
    ) {
        return PageResponseDto.from(productRepository.findAll(
                ProductSpecification.filter(keyword, status, deleted),
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "homeProductId"))
        ).map(AdminHomeProductResponseDto::from));
    }

    @Transactional(readOnly = true)
    public AdminHomeProductResponseDto getHomeProduct(Long homeProductId) {
        return AdminHomeProductResponseDto.from(productRepository.findById(homeProductId)
                .orElseThrow(() -> new HomeProductException(HomeProductErrorCode.PRODUCT_NOT_FOUND)));
    }

    public AdminHomeProductResponseDto updateHomeProduct(
            Long administratorId, Long homeProductId, AdminHomeProductUpdateRequestDto request
    ) {
        HomeProduct product = getUndeletedProduct(homeProductId);
        validateSpeed(product.getProductType(), request.speedMbps());
        if (changesConditions(product, request)
                && (subscriptionRepository.existsByHomeProductId(homeProductId)
                        || subscriptionRepository.existsBundleSubscriptionByHomeProductId(homeProductId))) {
            throw new HomeProductException(HomeProductErrorCode.PRODUCT_IN_USE);
        }
        product.update(
                userRepository.getReferenceById(administratorId),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.speedMbps(),
                request.monthlyFee()
        );
        log.info("인터넷/IPTV 상품을 수정했습니다: 상품ID={}", homeProductId);
        return AdminHomeProductResponseDto.from(product);
    }

    public AdminHomeProductResponseDto changeStatus(
            Long administratorId, Long homeProductId, ProductStatusUpdateRequestDto request
    ) {
        HomeProduct product = getUndeletedProduct(homeProductId);
        product.changeStatus(userRepository.getReferenceById(administratorId), request.status());
        return AdminHomeProductResponseDto.from(product);
    }

    public void deleteHomeProduct(Long administratorId, Long homeProductId) {
        HomeProduct product = getUndeletedProduct(homeProductId);
        if (subscriptionRepository.existsByHomeProductId(homeProductId)
                || subscriptionRepository.existsBundleProductByHomeProductId(homeProductId)) {
            throw new HomeProductException(HomeProductErrorCode.PRODUCT_IN_USE);
        }
        product.delete(userRepository.getReferenceById(administratorId));
        log.info("인터넷/IPTV 상품을 삭제했습니다: 상품ID={}", homeProductId);
    }

    private HomeProduct getUndeletedProduct(Long homeProductId) {
        return productRepository.findByIdForUpdate(homeProductId)
                .orElseThrow(() -> new HomeProductException(HomeProductErrorCode.PRODUCT_NOT_FOUND));
    }

    // 인터넷은 속도가 있어야 하고 IPTV는 속도가 없어야 한다.
    private void validateSpeed(HomeProductType productType, Integer speedMbps) {
        if ((productType == HomeProductType.INTERNET) != (speedMbps != null)) {
            throw new HomeProductException(HomeProductErrorCode.INVALID_PRODUCT);
        }
    }

    private String normalizeDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean changesConditions(HomeProduct product, AdminHomeProductUpdateRequestDto request) {
        return !Objects.equals(product.getDescription(), normalizeDescription(request.description()))
                || !Objects.equals(product.getSpeedMbps(), request.speedMbps())
                || !Objects.equals(product.getMonthlyFee(), request.monthlyFee());
    }
}
