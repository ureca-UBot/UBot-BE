package com.ubot.home.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.common.enums.MasterStatus;
import com.ubot.home.entity.HomeProduct;

public interface HomeProductRepository extends JpaRepository<HomeProduct, Long>, JpaSpecificationExecutor<HomeProduct> {

    boolean existsByCode(String code);

    Optional<HomeProduct> findByHomeProductIdAndStatusAndDeletedAtIsNull(Long homeProductId, MasterStatus status);

    // 가입 INSERT의 FK 잠금까지 기다려야 하므로 FOR UPDATE를 명시한다.
    @Query(value = """
            SELECT home_product_id, code, name, description, product_type, speed_mbps, monthly_fee, status,
                   created_by, updated_by, deleted_at, created_at, updated_at
            FROM home_products
            WHERE home_product_id = :homeProductId AND deleted_at IS NULL
            FOR UPDATE
            """, nativeQuery = true)
    Optional<HomeProduct> findByIdForUpdate(@Param("homeProductId") Long homeProductId);
}
