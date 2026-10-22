package com.ubot.telemap.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingQuestion;

public interface SurgingQuestionRepository extends JpaRepository<SurgingQuestion, Long> {

    boolean existsByQuestionLogId(Long questionLogId);

    @Query("""
            select new com.ubot.telemap.dto.SurgingQuestionSnapshot(
                q.questionLog.id,
                coalesce(q.questionLog.subQuestion, q.questionLog.userQuestion),
                q.occurredAt
            )
            from SurgingQuestion q
            where q.cluster.id = :clusterId
              and q.occurredAt >= :windowStart
            order by q.occurredAt, q.id
            """)
    List<SurgingQuestionSnapshot> findSnapshotsByClusterIdAndOccurredAtAfter(
            @Param("clusterId") Long clusterId,
            @Param("windowStart") LocalDateTime windowStart
    );
}
