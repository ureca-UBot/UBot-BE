package com.ubot.unanswered.scheduler;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ubot.global.config.AppRole;
import com.ubot.unanswered.service.UnansweredGroupNamingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Slf4j
@Component
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@ConditionalOnProperty(name = "unanswered.naming.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class UnansweredGroupNamingScheduler {

    private final UnansweredGroupNamingService namingService;

    @Scheduled(
            initialDelayString = "${UNANSWERED_NAMING_INTERVAL_MINUTES:5}",
            fixedDelayString = "${UNANSWERED_NAMING_INTERVAL_MINUTES:5}",
            timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "unansweredGroupNaming", lockAtMostFor = "PT10M")
    public void nameGroups() {
        try {
            namingService.nameGroups();
        } catch (RuntimeException exception) {
            log.error("미응답 묶음 이름 작업에 실패했습니다", exception);
        }
    }
}
