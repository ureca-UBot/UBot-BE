package com.ubot.telemap.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.entity.SurgingQuestion;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.telemap.repository.SurgingClusterRepository;
import com.ubot.telemap.repository.SurgingQuestionRepository;
import com.ubot.telemap.repository.TelemapRetentionRepository;
import com.ubot.telemap.repository.TelemapVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemapSurgingQuestionPersistenceService {

    private static final int MAX_SNAPSHOT_COUNT = 100;
    private static final long TELEMAP_REGION_LOCK_NAMESPACE = 0x544D_0000_0000_0000L;

    private final QuestionLogRepository questionLogRepository;
    private final SurgingQuestionRepository surgingQuestionRepository;
    private final SurgingClusterRepository surgingClusterRepository;
    private final SurgingAlertRepository surgingAlertRepository;
    private final TelemapVectorRepository telemapVectorRepository;
    private final TelemapRetentionRepository telemapRetentionRepository;
    private final TelemapProperties telemapProperties;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public void registerQuestion(long questionLogId, long profileId, PGvector vector) {
        if (surgingQuestionRepository.existsByQuestionLogId(questionLogId)) return;

        QuestionLog questionLog = questionLogRepository.findById(questionLogId)
                .orElseThrow(() -> new TelemapException(TelemapErrorCode.QUESTION_LOG_NOT_FOUND));
        RegionSido region = questionLog.getRegion();
        if (region == null || questionLog.getUserId() == null) return;

        String question = questionText(questionLog);
        Long userId = questionLog.getUserId();
        LocalDateTime occurredAt = questionLog.getCreatedAt();

        surgingClusterRepository.acquireRegionLock(lockKey(region, profileId));
        if (surgingQuestionRepository.existsByQuestionLogId(questionLogId)) return;

        telemapRetentionRepository.deleteExpiredClusters(
                region, profileId,
                occurredAt.minusMinutes(telemapProperties.getClusterMinimumAgeMinutes()),
                occurredAt.minusMinutes(telemapProperties.getWindowMinutes())
        );

        long clusterId = telemapVectorRepository.findNearestClusterId(
                        region, profileId, vector, telemapProperties.getClusterThreshold())
                .orElseGet(() -> telemapVectorRepository.createCluster(
                        region, question, profileId, vector, occurredAt));

        LocalDateTime participantWindowStart = occurredAt.minusMinutes(telemapProperties.getWindowMinutes());
        if (surgingQuestionRepository.existsByClusterIdAndUserIdAndOccurredAtBetween(
                clusterId, userId, participantWindowStart, occurredAt)) return;

        SurgingQuestion surgingQuestion = surgingQuestionRepository.saveAndFlush(
                SurgingQuestion.create(questionLog, surgingClusterRepository.getReferenceById(clusterId), occurredAt));
        telemapVectorRepository.saveQuestionEmbedding(surgingQuestion.getId(), profileId, vector);
        LocalDateTime windowEnd = telemapVectorRepository.updateClusterCentroid(clusterId, profileId, occurredAt);

        if (surgingClusterRepository.existsByIdAndAlertedAtIsNotNull(clusterId)) return;

        LocalDateTime windowStart = windowEnd.minusMinutes(telemapProperties.getWindowMinutes());
        long questionCount = surgingQuestionRepository.countByClusterIdAndOccurredAtBetween(clusterId, windowStart, windowEnd);
        if (questionCount < telemapProperties.getCountThreshold()) return;
        if (surgingClusterRepository.claimAlert(clusterId) == 0) return;

        List<SurgingQuestionSnapshot> snapshots = new ArrayList<>(
                surgingQuestionRepository.findRecentSnapshotsByClusterIdAndOccurredAtBetween(
                        clusterId, windowStart, windowEnd, PageRequest.of(0, MAX_SNAPSHOT_COUNT)));
        Collections.reverse(snapshots);

        SurgingAlert alert = surgingAlertRepository.save(SurgingAlert.create(
                region, snapshots, Math.toIntExact(questionCount), telemapProperties.getWindowMinutes()));
        outboxEventRepository.enqueue(
                OutboxEventType.SURGING_ALERT_LABEL,
                String.valueOf(alert.getId()),
                Map.of("alertId", alert.getId())
        );
        log.info("Surging question detected: alertId={}, clusterId={}, region={}, count={}",
                alert.getId(), clusterId, region, questionCount);
    }

    private String questionText(QuestionLog questionLog) {
        String subQuestion = questionLog.getSubQuestion();
        if (subQuestion != null && !subQuestion.isBlank()) return subQuestion;
        return questionLog.getUserQuestion();
    }

    private long lockKey(RegionSido region, long embeddingProfileId) {
        long regionProfileKey = ((long) region.ordinal() << 32) | (embeddingProfileId & 0xffffffffL);
        return TELEMAP_REGION_LOCK_NAMESPACE ^ regionProfileKey;
    }
}
