package com.ubot.telemap.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ubot.global.config.AppRole;
import com.ubot.telemap.service.TelemapRetentionService;
import com.ubot.telemap.service.TelemapRetentionService.RetentionResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@RequiredArgsConstructor
@Slf4j
public class TelemapRetentionScheduler {

    private final TelemapRetentionService telemapRetentionService;
    private final Clock clock;

    @Scheduled(cron = "${data-retention.purge-cron}", zone = "Asia/Seoul")
    @SchedulerLock(
            name = "telemapRetentionPurge",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT10M"
    )
    public void purgeExpiredData() {
        try {
            RetentionResult result = telemapRetentionService.purge(LocalDateTime.now(clock));
            if (result.clusterDeletionSkipped()) {
                log.info("통신 지도 질문 대기열이 남아 있어 만료 클러스터 삭제를 건너뜁니다: pending={}",
                        result.pendingQuestions());
            }
            log.info(
                    "통신 지도 보관 데이터를 정리했습니다: 익명화알림={}, 익명화클러스터={}, 삭제클러스터={}",
                    result.anonymizedAlerts(),
                    result.anonymizedClusters(),
                    result.deletedClusters()
            );
        } catch (RuntimeException exception) {
            log.error("통신 지도 보관 데이터 정리에 실패했습니다", exception);
        }
    }
}
