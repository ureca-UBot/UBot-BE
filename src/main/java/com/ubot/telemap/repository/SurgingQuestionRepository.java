package com.ubot.telemap.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingQuestion;

public interface SurgingQuestionRepository extends JpaRepository<SurgingQuestion, Long> {

    boolean existsByQuestionLogId(Long questionLogId);

    long countByClusterIdAndOccurredAtBetween(
            Long clusterId,
            LocalDateTime windowStart,
            LocalDateTime windowEnd
    );

    @Query("""
            select (count(q) > 0)
            from SurgingQuestion q
            where q.cluster.id = :clusterId
              and q.questionLog.userId = :userId
              and q.occurredAt >= :windowStart
              and q.occurredAt <= :windowEnd
            """)
    boolean existsByClusterIdAndUserIdAndOccurredAtBetween(
            @Param("clusterId") Long clusterId,
            @Param("userId") Long userId,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd
    );

    @Query("""
            select new com.ubot.telemap.dto.SurgingQuestionSnapshot(
                q.questionLog.id,
                case
                	when q.questionLog.subQuestion is null
                    or trim(q.questionLog.subQuestion) = ''
                    then q.questionLog.userQuestion
                    else q.questionLog.subQuestion
                end,
                q.occurredAt
            )
            from SurgingQuestion q
            where q.cluster.id = :clusterId
              and q.occurredAt >= :windowStart
              and q.occurredAt <= :windowEnd
            order by q.occurredAt desc, q.id desc
            """)
    List<SurgingQuestionSnapshot> findRecentSnapshotsByClusterIdAndOccurredAtBetween(
            @Param("clusterId") Long clusterId,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd,
            Pageable pageable
    );

}
