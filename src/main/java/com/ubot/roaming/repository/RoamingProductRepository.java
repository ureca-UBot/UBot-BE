package com.ubot.roaming.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.ubot.common.enums.MasterStatus;
import com.ubot.roaming.entity.RoamingProduct;

public interface RoamingProductRepository extends JpaRepository<RoamingProduct, Long>, JpaSpecificationExecutor<RoamingProduct> {

    boolean existsByCode(String code);

    Optional<RoamingProduct> findByRoamingProductIdAndDeletedAtIsNull(Long roamingProductId);

    Optional<RoamingProduct> findByRoamingProductIdAndStatusAndDeletedAtIsNull(Long roamingProductId, MasterStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from RoamingProduct product where product.roamingProductId = :roamingProductId and product.deletedAt is null")
    Optional<RoamingProduct> findByIdForUpdate(@Param("roamingProductId") Long roamingProductId);
}
