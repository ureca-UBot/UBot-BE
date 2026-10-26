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

    // 처리 시각은 updated_at을 쓰므로 질문 수만 바꾸고 updated_at, 상태, 스냅샷은 건드리지 않는다.
    // 클러스터에 질문이 남아 있지 않으면 집계 결과에 나오지 않아 갱신되지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE surging_alerts a
            SET question_count = c.question_count
            FROM (
                SELECT sq.cluster_id, COUNT(*) AS question_count
                FROM surging_questions sq
                WHERE sq.cluster_id IN (
                    SELECT pending.cluster_id
                    FROM surging_alerts pending
                    WHERE pending.status = 'PENDING'
                      AND pending.cluster_id IS NOT NULL
                )
                GROUP BY sq.cluster_id
            ) c
            WHERE a.cluster_id = c.cluster_id
              AND a.status = 'PENDING'
              AND a.question_count <> c.question_count
            """, nativeQuery = true)
    int refreshPendingQuestionCounts();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SurgingAlert a
            set a.subject = :subject,
                a.updatedAt = :updatedAt
            where a.id = :alertId
              and (a.subject is null or trim(a.subject) = '')
            """)
    int updateSubjectIfBlank(
            @Param("alertId") Long alertId,
            @Param("subject") String subject,
            @Param("updatedAt") LocalDateTime updatedAt
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
