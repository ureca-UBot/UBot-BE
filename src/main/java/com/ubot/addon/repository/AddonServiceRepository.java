package com.ubot.addon.repository;

import java.util.Optional;

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

    // 가입 INSERT의 FK 잠금까지 기다려야 하므로 FOR UPDATE를 명시한다.
    @Query(value = "SELECT * FROM addon_services WHERE addon_service_id = :addonServiceId AND deleted_at IS NULL FOR UPDATE", nativeQuery = true)
    Optional<AddonService> findByIdForUpdate(@Param("addonServiceId") Long addonServiceId);
}
