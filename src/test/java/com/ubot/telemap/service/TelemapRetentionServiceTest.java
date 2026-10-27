package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.outbox.OutboxWorker;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.repository.TelemapRetentionRepository;

@DisplayName("통신 지도 보존 서비스")
class TelemapRetentionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    private final TelemapRetentionRepository retention = mock(TelemapRetentionRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TelemapProperties properties = new TelemapProperties();
    private final TelemapRetentionService service = new TelemapRetentionService(retention, properties, outbox);

    @BeforeEach
    void setUp() {
        properties.setWindowMinutes(10);
        properties.setClusterMinimumAgeMinutes(1_440);
    }

    @Test
    @DisplayName("질문 스냅샷은 질문 발생 시각 기준 3개월 이전을 익명화한다")
    void anonymizesSnapshotsUsingOccurrenceCutoff() {
        LocalDateTime cutoff = NOW.minusMonths(3);

        service.purge(NOW);

        verify(retention).anonymizeAlertSnapshots(cutoff);
        verify(retention).anonymizeClusterRepresentativeQuestions(cutoff);
    }

    @Test
    @DisplayName("처리할 급증 질문 이벤트가 남아 있으면 클러스터 삭제를 건너뛴다")
    void skipsClusterDeletionWhileQuestionEventsArePending() {
        when(outbox.countPending(OutboxEventType.SURGING_QUESTION, OutboxWorker.MAX_RETRIES))
                .thenReturn(2L);

        TelemapRetentionService.RetentionResult result = service.purge(NOW);

        verify(retention, never()).deleteExpiredClusters(
                NOW.minusMinutes(1_440),
                NOW.minusMinutes(10)
        );
        assertThat(result.clusterDeletionSkipped()).isTrue();
        assertThat(result.pendingQuestions()).isEqualTo(2L);
    }

    @Test
    @DisplayName("처리할 급증 질문 이벤트가 없으면 하루 경과 및 10분 비활성 기준으로 삭제한다")
    void deletesClustersWhenNoQuestionEventsArePending() {
        when(outbox.countPending(OutboxEventType.SURGING_QUESTION, OutboxWorker.MAX_RETRIES))
                .thenReturn(0L);
        when(retention.deleteExpiredClusters(NOW.minusDays(1), NOW.minusMinutes(10)))
                .thenReturn(3);

        TelemapRetentionService.RetentionResult result = service.purge(NOW);

        verify(retention).deleteExpiredClusters(NOW.minusDays(1), NOW.minusMinutes(10));
        assertThat(result.deletedClusters()).isEqualTo(3);
        assertThat(result.clusterDeletionSkipped()).isFalse();
    }
}
