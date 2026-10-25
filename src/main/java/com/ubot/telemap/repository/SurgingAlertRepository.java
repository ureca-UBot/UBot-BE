package com.ubot.telemap.repository;

import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;
import com.ubot.user.entity.User;

public interface SurgingAlertRepository extends JpaRepository<SurgingAlert, Long> {

    @EntityGraph(attributePaths = "updatedBy")
    @Query("""
            select a
            from SurgingAlert a
            where (:status is null or a.status = :status)
              and (:region is null or a.region = :region)
            order by a.createdAt desc, a.id desc
            """)
    Page<SurgingAlert> findAllByFilters(
            @Param("status") SurgingAlertStatus status,
            @Param("region") RegionSido region,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SurgingAlert a
            set a.status = com.ubot.telemap.enums.SurgingAlertStatus.PROCESSED,
                a.updatedBy = :administrator,
                a.updatedAt = :processedAt
            where a.id = :alertId
              and a.status = com.ubot.telemap.enums.SurgingAlertStatus.PENDING
            """)
    int markProcessedIfPending(
            @Param("alertId") Long alertId,
            @Param("administrator") User administrator,
            @Param("processedAt") LocalDateTime processedAt
    );
}
