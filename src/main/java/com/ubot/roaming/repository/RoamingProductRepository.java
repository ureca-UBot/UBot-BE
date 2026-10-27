package com.ubot.roaming.repository;

import java.util.Optional;

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

    // 가입 INSERT의 FK 잠금까지 기다려야 하므로 FOR UPDATE를 명시한다.
    @Query(value = "SELECT * FROM roaming_products WHERE roaming_product_id = :roamingProductId AND deleted_at IS NULL FOR UPDATE", nativeQuery = true)
    Optional<RoamingProduct> findByIdForUpdate(@Param("roamingProductId") Long roamingProductId);
}
