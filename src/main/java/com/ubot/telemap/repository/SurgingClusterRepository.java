package com.ubot.telemap.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.telemap.entity.SurgingCluster;

public interface SurgingClusterRepository extends JpaRepository<SurgingCluster, Long> {

    @Query(value = "SELECT pg_advisory_xact_lock(:lockKey)", nativeQuery = true)
    void acquireRegionLock(@Param("lockKey") long lockKey);

    @Modifying
    @Query("""
            update SurgingCluster c
            set c.alertedAt = CURRENT_TIMESTAMP,
                c.updatedAt = CURRENT_TIMESTAMP
            where c.id = :clusterId
              and c.alertedAt is null
            """)
    int claimAlert(@Param("clusterId") Long clusterId);
}
