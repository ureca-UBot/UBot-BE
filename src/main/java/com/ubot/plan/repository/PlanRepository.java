package com.ubot.plan.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.ubot.common.enums.MasterStatus;
import com.ubot.plan.entity.Plan;

public interface PlanRepository extends JpaRepository<Plan, Long>, JpaSpecificationExecutor<Plan> {

    boolean existsByPlanCode(String planCode);

    Optional<Plan> findByPlanIdAndDeletedAtIsNull(Long planId);

    Optional<Plan> findByPlanIdAndStatusAndDeletedAtIsNull(Long planId, MasterStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from Plan product where product.planId = :planId and product.deletedAt is null")
    Optional<Plan> findByIdForUpdate(@Param("planId") Long planId);
}
