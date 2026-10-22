package com.ubot.telemap.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.entity.SurgingQuestion;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.telemap.repository.SurgingClusterRepository;
import com.ubot.telemap.repository.SurgingQuestionRepository;
import com.ubot.telemap.repository.TelemapVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemapSurgingQuestionService {

    private final QuestionLogRepository questionLogRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final SurgingQuestionRepository surgingQuestionRepository;
    private final SurgingClusterRepository surgingClusterRepository;
    private final SurgingAlertRepository surgingAlertRepository;
    private final TelemapVectorRepository telemapVectorRepository;

    @Value("${TELEMAP_WINDOW_MINUTES:10}")
    private int windowMinutes;

    @Value("${TELEMAP_COUNT_THRESHOLD:100}")
    private int countThreshold;

    @Value("${TELEMAP_CLUSTER_THRESHOLD:0.6}")
    private double clusterThreshold;

    @Value("${TELEMAP_RETENTION_MINUTES:60}")
    private int retentionMinutes;

    public void registerQuestion(long questionLogId) {
        if (surgingQuestionRepository.existsByQuestionLogId(questionLogId)) return;

        QuestionLog questionLog = questionLogRepository.findById(questionLogId)
                .orElseThrow(() -> new TelemapException(TelemapErrorCode.QUESTION_LOG_NOT_FOUND));
        RegionSido region = questionLog.getRegion();
        if (region == null) {
            log.debug("지역 정보가 없는 질문 로그는 급증 감지에서 제외합니다: questionLogId={}", questionLogId);
            return;
        }

        String question = questionText(questionLog);
        long profileId = embeddingProfileService.getCurrentProfileId();
        PGvector vector = embeddingService.embedText(question);
        LocalDateTime occurredAt = questionLog.getCreatedAt();
        LocalDateTime now = LocalDateTime.now();

        surgingClusterRepository.acquireRegionLock(lockKey(region, profileId));
        if (surgingQuestionRepository.existsByQuestionLogId(questionLogId)) return;

        long clusterId = telemapVectorRepository.findNearestClusterId(
                        region,
                        profileId,
                        vector,
                        clusterThreshold,
                        now.minusMinutes(retentionMinutes)
                )
                .orElseGet(() -> telemapVectorRepository.createCluster(
                        region, question, profileId, vector, occurredAt));

        SurgingQuestion surgingQuestion = surgingQuestionRepository.saveAndFlush(
                SurgingQuestion.create(questionLog, surgingClusterRepository.getReferenceById(clusterId), occurredAt));
        telemapVectorRepository.saveQuestionEmbedding(surgingQuestion.getId(), profileId, vector);
        telemapVectorRepository.updateClusterCentroid(clusterId, profileId, occurredAt);

        List<SurgingQuestionSnapshot> snapshots = surgingQuestionRepository.findSnapshotsByClusterIdAndOccurredAtAfter(
                clusterId, now.minusMinutes(windowMinutes));
        if (snapshots.size() < countThreshold) return;

        if (surgingClusterRepository.claimAlert(clusterId) == 0) return;
        SurgingAlert alert = surgingAlertRepository.save(SurgingAlert.create(region, snapshots, windowMinutes));
        log.info("지역 문의 급증을 감지했습니다: alertId={}, clusterId={}, region={}, count={}",
                alert.getId(), clusterId, region, snapshots.size());
    }

    private String questionText(QuestionLog questionLog) {
        String subQuestion = questionLog.getSubQuestion();
        if (subQuestion != null && !subQuestion.isBlank()) return subQuestion;
        return questionLog.getUserQuestion();
    }

    private long lockKey(RegionSido region, long embeddingProfileId) {
        return ((long) region.ordinal() << 32) | (embeddingProfileId & 0xffffffffL);
    }
}
