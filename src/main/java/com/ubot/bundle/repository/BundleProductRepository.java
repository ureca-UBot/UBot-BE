package com.ubot.bundle.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from BundleProduct product where product.bundleProductId = :bundleProductId and product.deletedAt is null")
    Optional<BundleProduct> findByIdForUpdate(@Param("bundleProductId") Long bundleProductId);
}
