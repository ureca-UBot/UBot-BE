package com.ubot.addon.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.ubot.common.enums.MasterStatus;
import com.ubot.addon.entity.AddonService;

public interface AddonServiceRepository extends JpaRepository<AddonService, Long>, JpaSpecificationExecutor<AddonService> {

    boolean existsByCode(String code);

    Optional<AddonService> findByAddonServiceIdAndDeletedAtIsNull(Long addonServiceId);

    Optional<AddonService> findByAddonServiceIdAndStatusAndDeletedAtIsNull(Long addonServiceId, MasterStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from AddonService product where product.addonServiceId = :addonServiceId and product.deletedAt is null")
    Optional<AddonService> findByIdForUpdate(@Param("addonServiceId") Long addonServiceId);
}
