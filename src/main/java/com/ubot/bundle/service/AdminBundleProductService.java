package com.ubot.bundle.service;

import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.common.repository.ProductSubscriptionRepository;
import com.ubot.common.repository.ProductWriteSupport;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.bundle.dto.request.AdminBundleProductCreateRequestDto;
import com.ubot.bundle.dto.request.AdminBundleProductUpdateRequestDto;
import com.ubot.bundle.dto.response.AdminBundleProductResponseDto;
import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.enums.BundleType;
import com.ubot.bundle.exception.BundleProductErrorCode;
import com.ubot.bundle.exception.BundleProductException;
import com.ubot.bundle.repository.BundleProductRepository;
import com.ubot.home.entity.HomeProduct;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.repository.HomeProductRepository;
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
    private final ProductSubscriptionRepository subscriptionRepository;
    private final HomeProductRepository homeProductRepository;

    public AdminBundleProductResponseDto createBundleProduct(Long administratorId, AdminBundleProductCreateRequestDto request) {
        BundleType bundleType = request.bundleType() == null ? BundleType.ETC : request.bundleType();
        validateHomeBundle(bundleType, request.homeProductId());
        if (productRepository.existsByCode(request.code())) {
            throw new BundleProductException(BundleProductErrorCode.DUPLICATE_CODE);
        }
        BundleProduct product = BundleProduct.create(
                userRepository.getReferenceById(administratorId),
                request.code(),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.discountAmount(),
                bundleType,
                request.lineCount(),
                getInternetProduct(request.homeProductId())
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
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "bundleProductId"))
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
        BundleType bundleType = request.bundleType() == null ? product.getBundleType() : request.bundleType();
        validateHomeBundle(bundleType, request.homeProductId() == null && product.getHomeProduct() != null
                ? product.getHomeProduct().getHomeProductId() : request.homeProductId());
        if (changesConditions(product, request) && subscriptionRepository.existsByBundleProductId(bundleProductId)) {
            throw new BundleProductException(BundleProductErrorCode.PRODUCT_IN_USE);
        }
        product.update(
                userRepository.getReferenceById(administratorId),
                request.name().trim(),
                normalizeDescription(request.description()),
                request.discountAmount(),
                bundleType,
                request.lineCount() == null ? product.getLineCount() : request.lineCount(),
                request.homeProductId() == null ? product.getHomeProduct() : getInternetProduct(request.homeProductId())
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
        BundleProduct product = getUndeletedProduct(bundleProductId);
        if (subscriptionRepository.existsByBundleProductId(bundleProductId)) {
            throw new BundleProductException(BundleProductErrorCode.PRODUCT_IN_USE);
        }
        product.delete(userRepository.getReferenceById(administratorId));
        log.info("결합 상품을 삭제했습니다: 상품ID={}", bundleProductId);
    }

    private BundleProduct getUndeletedProduct(Long bundleProductId) {
        return productRepository.findByIdForUpdate(bundleProductId)
                .orElseThrow(() -> new BundleProductException(BundleProductErrorCode.PRODUCT_NOT_FOUND));
    }

    // 홈 결합에는 삭제되지 않은 인터넷 상품만 연결한다.
    private HomeProduct getInternetProduct(Long homeProductId) {
        if (homeProductId == null) {
            return null;
        }
        return homeProductRepository.findById(homeProductId)
                .filter(homeProduct -> homeProduct.getDeletedAt() == null)
                .filter(homeProduct -> homeProduct.getProductType() == HomeProductType.INTERNET)
                .orElseThrow(() -> new BundleProductException(BundleProductErrorCode.INVALID_PRODUCT));
    }

    // 홈 결합은 인터넷 상품이 있어야 할인을 판정할 수 있다. homeProductId는 반영 후의 최종 값이다.
    private void validateHomeBundle(BundleType bundleType, Long homeProductId) {
        if (bundleType == BundleType.HOME && homeProductId == null) {
            throw new BundleProductException(BundleProductErrorCode.INVALID_PRODUCT);
        }
    }

    private String normalizeDescription(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean changesConditions(BundleProduct product, AdminBundleProductUpdateRequestDto request) {
        return !Objects.equals(product.getDescription(), normalizeDescription(request.description()))
                || !Objects.equals(product.getDiscountAmount(), request.discountAmount())
                || (request.bundleType() != null && product.getBundleType() != request.bundleType())
                || (request.lineCount() != null && !Objects.equals(product.getLineCount(), request.lineCount()))
                || (request.homeProductId() != null && (product.getHomeProduct() == null
                        || !Objects.equals(product.getHomeProduct().getHomeProductId(), request.homeProductId())));
    }
}
