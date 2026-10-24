package com.ubot.telemap.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.outbox.OutboxWorker;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.repository.TelemapRetentionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TelemapRetentionService {

    private final TelemapRetentionRepository telemapRetentionRepository;
    private final TelemapProperties telemapProperties;
    private final OutboxEventRepository outboxEventRepository;

    public RetentionResult purge(LocalDateTime now) {
        LocalDateTime occurredBefore = now.minusMonths(3);
        int anonymizedAlerts = telemapRetentionRepository.anonymizeAlertSnapshots(occurredBefore);
        int anonymizedClusters = telemapRetentionRepository.anonymizeClusterRepresentativeQuestions(occurredBefore);
        long pendingQuestions = outboxEventRepository.countPending(
                OutboxEventType.SURGING_QUESTION,
                OutboxWorker.MAX_RETRIES
        );
        int deletedClusters = pendingQuestions == 0
                ? telemapRetentionRepository.deleteExpiredClusters(
                        now.minusMinutes(telemapProperties.getClusterMinimumAgeMinutes()),
                        now.minusMinutes(telemapProperties.getWindowMinutes()))
                : 0;
        return new RetentionResult(anonymizedAlerts, anonymizedClusters, deletedClusters, pendingQuestions);
    }

    public record RetentionResult(
            int anonymizedAlerts,
            int anonymizedClusters,
            int deletedClusters,
            long pendingQuestions
    ) {
        public boolean clusterDeletionSkipped() {
            return pendingQuestions > 0;
        }
    }
}
