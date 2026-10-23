package com.ubot.home.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.home.dto.response.HomeProductResponseDto;
import com.ubot.home.entity.HomeProduct;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.exception.HomeProductErrorCode;
import com.ubot.home.exception.HomeProductException;
import com.ubot.home.repository.HomeProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeProductService {

    private final HomeProductRepository productRepository;

    public PageResponseDto<HomeProductResponseDto> getHomeProductList(
            String keyword, HomeProductType productType, int page, int size
    ) {
        Specification<HomeProduct> specification = ProductSpecification.filter(keyword, MasterStatus.ACTIVE, false);
        if (productType != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("productType"), productType));
        }
        return PageResponseDto.from(productRepository.findAll(
                specification,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "homeProductId"))
        ).map(HomeProductResponseDto::from));
    }

    public HomeProductResponseDto getHomeProduct(Long homeProductId) {
        return HomeProductResponseDto.from(productRepository
                .findByHomeProductIdAndStatusAndDeletedAtIsNull(homeProductId, MasterStatus.ACTIVE)
                .orElseThrow(() -> new HomeProductException(HomeProductErrorCode.PRODUCT_NOT_FOUND)));
    }
}
