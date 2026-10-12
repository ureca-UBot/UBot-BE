package com.ubot.bundle.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.ubot.common.enums.MasterStatus;
import com.ubot.bundle.entity.BundleProduct;

public interface BundleProductRepository extends JpaRepository<BundleProduct, Long>, JpaSpecificationExecutor<BundleProduct> {

    boolean existsByCode(String code);

    Optional<BundleProduct> findByBundleProductIdAndDeletedAtIsNull(Long bundleProductId);

    Optional<BundleProduct> findByBundleProductIdAndStatusAndDeletedAtIsNull(Long bundleProductId, MasterStatus status);

    // 가입 INSERT의 FK 잠금까지 기다려야 하므로 FOR UPDATE를 명시한다.
    @Query(value = "SELECT * FROM bundle_products WHERE bundle_product_id = :bundleProductId AND deleted_at IS NULL FOR UPDATE", nativeQuery = true)
    Optional<BundleProduct> findByIdForUpdate(@Param("bundleProductId") Long bundleProductId);
}
