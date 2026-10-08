package com.ubot.roaming.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.PageResponseDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.roaming.dto.response.RoamingProductResponseDto;
import com.ubot.roaming.entity.RoamingProduct;
import com.ubot.roaming.exception.RoamingProductErrorCode;
import com.ubot.roaming.exception.RoamingProductException;
import com.ubot.roaming.repository.RoamingProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoamingCatalogService {

    private final RoamingProductRepository productRepository;

    public PageResponseDto<RoamingProductResponseDto> getRoamingProductList(
            String keyword, String country, int page, int size
    ) {
        Specification<RoamingProduct> specification = ProductSpecification.filter(keyword, MasterStatus.ACTIVE, false);
        if (country != null && !country.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("country"), country.trim()));
        }
        return PageResponseDto.from(productRepository.findAll(
                specification,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "roamingProductId"))
        ).map(RoamingProductResponseDto::from));
    }

    public RoamingProductResponseDto getRoamingProduct(Long roamingProductId) {
        return RoamingProductResponseDto.from(productRepository
                .findByRoamingProductIdAndStatusAndDeletedAtIsNull(roamingProductId, MasterStatus.ACTIVE)
                .orElseThrow(() -> new RoamingProductException(RoamingProductErrorCode.PRODUCT_NOT_FOUND)));
    }
}
