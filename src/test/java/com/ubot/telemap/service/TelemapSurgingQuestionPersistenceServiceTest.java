package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pgvector.PGvector;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.entity.SurgingCluster;
import com.ubot.telemap.entity.SurgingQuestion;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.telemap.repository.SurgingClusterRepository;
import com.ubot.telemap.repository.SurgingQuestionRepository;
import com.ubot.telemap.repository.TelemapRetentionRepository;
import com.ubot.telemap.repository.TelemapVectorRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("급증 질문 등록 영속성 서비스")
class TelemapSurgingQuestionPersistenceServiceTest {

    private final QuestionLogRepository questionLogs = mock(QuestionLogRepository.class);
    private final SurgingQuestionRepository surgingQuestions = mock(SurgingQuestionRepository.class);
    private final SurgingClusterRepository clusters = mock(SurgingClusterRepository.class);
    private final SurgingAlertRepository alerts = mock(SurgingAlertRepository.class);
    private final TelemapVectorRepository vectors = mock(TelemapVectorRepository.class);
    private final TelemapRetentionRepository retention = mock(TelemapRetentionRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TelemapProperties properties = new TelemapProperties();
    private final TelemapSurgingQuestionPersistenceService service = new TelemapSurgingQuestionPersistenceService(
            questionLogs, surgingQuestions, clusters, alerts, vectors, retention, properties, outbox);

    private static final PGvector VECTOR = new PGvector(new float[] {1, 0});

    @BeforeEach
    void setUp() {
        properties.setWindowMinutes(10);
        properties.setCountThreshold(3);
        properties.setClusterThreshold(0.7);
        properties.setClusterMinimumAgeMinutes(1_440);
    }

    @Test
    @DisplayName("이미 등록된 질문 로그는 재처리하지 않는다 (잠금 전 existsByQuestionLogId)")
    void ignoresAlreadyRegisteredQuestionBeforeLock() {
        when(surgingQuestions.existsByQuestionLogId(1L)).thenReturn(true);

        service.registerQuestion(1L, 7L, VECTOR);

        verifyNoInteractions(questionLogs, clusters, alerts, vectors, retention, outbox);
    }

    @Test
    @DisplayName("잠금을 기다리는 동안 다른 작업이 먼저 등록했으면 중복 저장하지 않는다 (잠금 후 existsByQuestionLogId)")
    void ignoresAlreadyRegisteredQuestionAfterLock() {
        QuestionLog questionLog = questionLog(3L, RegionSido.SEOUL, 1L, "질문", "원문");
        when(surgingQuestions.existsByQuestionLogId(3L)).thenReturn(false, true);
        when(questionLogs.findById(3L)).thenReturn(Optional.of(questionLog));

        service.registerQuestion(3L, 1L, VECTOR);

        verify(clusters).acquireRegionLock(anyLong());
        verifyNoInteractions(retention, vectors, alerts, outbox);
        verify(surgingQuestions, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("게스트(사용자 없음) 질문은 급증 감지에서 제외한다")
    void excludesGuestQuestion() {
        QuestionLog questionLog = questionLog(1L, RegionSido.SEOUL, null, "질문", "원문");
        when(questionLogs.findById(1L)).thenReturn(Optional.of(questionLog));

        service.registerQuestion(1L, 7L, VECTOR);

        verifyNoInteractions(clusters, alerts, vectors, retention, outbox);
    }

    @Test
    @DisplayName("지역 정보가 없는 질문은 급증 감지에서 제외한다")
    void excludesQuestionWithoutRegion() {
        QuestionLog questionLog = questionLog(1L, null, 1L, "질문", "원문");
        when(questionLogs.findById(1L)).thenReturn(Optional.of(questionLog));

        service.registerQuestion(1L, 7L, VECTOR);

        verifyNoInteractions(clusters, alerts, vectors, retention, outbox);
    }

    @Test
    @DisplayName("같은 사용자가 window 안에서 같은 클러스터에 이미 참여했으면 다시 저장하지 않는다")
    void excludesRepeatedParticipationWithinWindow() {
        QuestionLog questionLog = questionLog(4L, RegionSido.SEOUL, 1L, "질문", "원문");
        when(questionLogs.findById(4L)).thenReturn(Optional.of(questionLog));
        when(vectors.findNearestClusterId(RegionSido.SEOUL, 7L, VECTOR, 0.7)).thenReturn(Optional.of(11L));
        when(clusters.getReferenceById(11L)).thenReturn(mock(SurgingCluster.class));
        when(surgingQuestions.existsByClusterIdAndUserIdAndOccurredAtBetween(eq(11L), eq(1L), any(), any()))
                .thenReturn(true);

        service.registerQuestion(4L, 7L, VECTOR);

        verify(surgingQuestions, never()).saveAndFlush(any());
        verifyNoInteractions(alerts, outbox);
    }

    @Test
    @DisplayName("이미 알림이 발생한 클러스터는 건수와 스냅샷을 다시 집계하지 않는다")
    void skipsAlertAggregationForAlreadyAlertedCluster() {
        prepareStoredQuestion(6L, 16L, List.of(snapshot(1L), snapshot(2L), snapshot(3L)));
        when(clusters.existsByIdAndAlertedAtIsNotNull(16L)).thenReturn(true);

        service.registerQuestion(6L, 7L, VECTOR);

        verify(surgingQuestions, never()).countByClusterIdAndOccurredAtBetween(anyLong(), any(), any());
        verify(clusters, never()).claimAlert(anyLong());
        verifyNoInteractions(alerts, outbox);
    }

    @Test
    @DisplayName("임계값에 도달하면 알림을 1회 생성하고 레이블링 이벤트를 발행한다")
    void createsAlertOnceWhenThresholdReached() {
        prepareStoredQuestion(5L, 15L, List.of(snapshot(1L), snapshot(2L), snapshot(3L)));
        when(clusters.claimAlert(15L)).thenReturn(1);
        SurgingAlert savedAlert = mock(SurgingAlert.class);
        when(savedAlert.getId()).thenReturn(45L);
        when(alerts.save(any(SurgingAlert.class))).thenReturn(savedAlert);

        service.registerQuestion(5L, 7L, VECTOR);

        verify(alerts).save(any(SurgingAlert.class));
        verify(outbox).enqueue(OutboxEventType.SURGING_ALERT_LABEL, "45", Map.of("alertId", 45L));
    }

    @Test
    @DisplayName("다른 작업이 알림을 먼저 선점했으면 중복 알림을 만들지 않는다")
    void doesNotCreateAlertWhenClaimFails() {
        prepareStoredQuestion(5L, 15L, List.of(snapshot(1L), snapshot(2L), snapshot(3L)));
        when(clusters.claimAlert(15L)).thenReturn(0);

        service.registerQuestion(5L, 7L, VECTOR);

        verifyNoInteractions(alerts, outbox);
    }

    private void prepareStoredQuestion(long questionLogId, long clusterId, List<SurgingQuestionSnapshot> snapshots) {
        QuestionLog questionLog = questionLog(questionLogId, RegionSido.SEOUL, 1L, "질문", "원문");
        SurgingQuestion saved = mock(SurgingQuestion.class);
        when(saved.getId()).thenReturn(questionLogId + 100);
        when(questionLogs.findById(questionLogId)).thenReturn(Optional.of(questionLog));
        when(vectors.findNearestClusterId(RegionSido.SEOUL, 7L, VECTOR, 0.7)).thenReturn(Optional.of(clusterId));
        when(clusters.getReferenceById(clusterId)).thenReturn(mock(SurgingCluster.class));
        when(surgingQuestions.saveAndFlush(any())).thenReturn(saved);
        when(vectors.updateClusterCentroid(eq(clusterId), eq(7L), any())).thenReturn(questionLog.getCreatedAt());
        when(surgingQuestions.countByClusterIdAndOccurredAtBetween(eq(clusterId), any(), any()))
                .thenReturn((long) snapshots.size());
        when(surgingQuestions.findRecentSnapshotsByClusterIdAndOccurredAtBetween(
                eq(clusterId), any(), any(), any()))
                .thenReturn(snapshots);
    }

    private static QuestionLog questionLog(
            long id, RegionSido region, Long userId, String subQuestion, String userQuestion) {
        QuestionLog questionLog = QuestionLog.builder()
                .userId(userId)
                .userQuestion(userQuestion)
                .subQuestion(subQuestion)
                .normalizedQuestion(userQuestion)
                .rankingEligible(true)
                .region(region)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(questionLog, "id", id);
        return questionLog;
    }

    private static SurgingQuestionSnapshot snapshot(long questionLogId) {
        return new SurgingQuestionSnapshot(questionLogId, "질문 " + questionLogId, LocalDateTime.now());
    }
}
