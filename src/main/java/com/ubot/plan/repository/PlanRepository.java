package com.ubot.plan.repository;

import java.util.Optional;

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

    // 가입 INSERT의 FK 잠금까지 기다려야 하므로 FOR UPDATE를 명시한다.
    @Query(value = "SELECT * FROM plans WHERE plan_id = :planId AND deleted_at IS NULL FOR UPDATE", nativeQuery = true)
    Optional<Plan> findByIdForUpdate(@Param("planId") Long planId);
}
