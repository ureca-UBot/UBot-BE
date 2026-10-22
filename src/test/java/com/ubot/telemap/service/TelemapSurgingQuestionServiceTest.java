package com.ubot.telemap.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pgvector.PGvector;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.config.TelemapProperties;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.entity.SurgingCluster;
import com.ubot.telemap.entity.SurgingQuestion;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import com.ubot.telemap.repository.SurgingAlertRepository;
import com.ubot.telemap.repository.SurgingClusterRepository;
import com.ubot.telemap.repository.SurgingQuestionRepository;
import com.ubot.telemap.repository.TelemapVectorRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("급증 질문 등록 서비스")
class TelemapSurgingQuestionServiceTest {

    private final QuestionLogRepository questionLogs = mock(QuestionLogRepository.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
    private final SurgingQuestionRepository surgingQuestions = mock(SurgingQuestionRepository.class);
    private final SurgingClusterRepository clusters = mock(SurgingClusterRepository.class);
    private final SurgingAlertRepository alerts = mock(SurgingAlertRepository.class);
    private final TelemapVectorRepository vectors = mock(TelemapVectorRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TelemapProperties properties = new TelemapProperties();
    private final TelemapSurgingQuestionService service = new TelemapSurgingQuestionService(
            questionLogs, embeddings, profiles, surgingQuestions, clusters, alerts, vectors, properties, outbox);

    @BeforeEach
    void setUp() {
        properties.setWindowMinutes(10);
        properties.setCountThreshold(3);
        properties.setClusterThreshold(0.7);
        properties.setRetentionMinutes(60);
    }

    @Test
    @DisplayName("이미 등록된 질문 로그는 다시 처리하지 않는다")
    void ignoresAlreadyRegisteredQuestion() {
        when(surgingQuestions.existsByQuestionLogId(1L)).thenReturn(true);

        service.registerQuestion(1L);

        verifyNoInteractions(questionLogs, embeddings, profiles, clusters, alerts, vectors, outbox);
    }

    @Test
    @DisplayName("존재하지 않는 질문 로그를 거부한다")
    void rejectsMissingQuestionLog() {
        when(questionLogs.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerQuestion(9L))
                .isInstanceOfSatisfying(TelemapException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(TelemapErrorCode.QUESTION_LOG_NOT_FOUND));
    }

    @Test
    @DisplayName("지역 정보가 없는 질문은 임베딩하지 않는다")
    void ignoresQuestionWithoutRegion() {
        QuestionLog questionLog = questionLog(1L, null, "하위 질문", "원문 질문");
        when(questionLogs.findById(1L)).thenReturn(Optional.of(questionLog));

        service.registerQuestion(1L);

        verifyNoInteractions(embeddings, profiles, clusters, alerts, vectors, outbox);
    }

    @Test
    @DisplayName("기존 유사 클러스터에 질문을 저장하고 임계값 미만이면 알림을 만들지 않는다")
    void joinsNearestClusterWithoutAlertBelowThreshold() {
        PGvector vector = new PGvector(new float[] {1, 0});
        QuestionLog questionLog = questionLog(1L, RegionSido.SEOUL, "5G 장애 문의", "원문 질문");
        SurgingCluster cluster = mock(SurgingCluster.class);
        SurgingQuestion saved = mock(SurgingQuestion.class);
        when(saved.getId()).thenReturn(31L);
        when(questionLogs.findById(1L)).thenReturn(Optional.of(questionLog));
        when(profiles.getCurrentProfileId()).thenReturn(7L);
        when(embeddings.embedText("5G 장애 문의")).thenReturn(vector);
        when(vectors.findNearestClusterId(eq(RegionSido.SEOUL), eq(7L), eq(vector), eq(0.7), any()))
                .thenReturn(Optional.of(11L));
        when(clusters.getReferenceById(11L)).thenReturn(cluster);
        when(surgingQuestions.saveAndFlush(any())).thenReturn(saved);
        when(surgingQuestions.findSnapshotsByClusterIdAndOccurredAtAfter(eq(11L), any()))
                .thenReturn(List.of(snapshot(1L)));

        service.registerQuestion(1L);

        verify(vectors, never()).createCluster(any(), any(), anyLong(), any(), any());
        InOrder order = inOrder(clusters, surgingQuestions, vectors);
        order.verify(clusters).acquireRegionLock(anyLong());
        order.verify(surgingQuestions).saveAndFlush(any(SurgingQuestion.class));
        order.verify(vectors).saveQuestionEmbedding(31L, 7L, vector);
        order.verify(vectors).updateClusterCentroid(eq(11L), eq(7L), any());
        verifyNoInteractions(alerts, outbox);
    }

    @Test
    @DisplayName("유사 클러스터가 없으면 원문 질문으로 새 클러스터를 만든다")
    void createsClusterAndUsesOriginalWhenSubQuestionIsBlank() {
        PGvector vector = new PGvector(new float[] {0, 1});
        QuestionLog questionLog = questionLog(2L, RegionSido.BUSAN, "  ", "인터넷이 느려요");
        SurgingQuestion saved = mock(SurgingQuestion.class);
        when(saved.getId()).thenReturn(32L);
        when(questionLogs.findById(2L)).thenReturn(Optional.of(questionLog));
        when(profiles.getCurrentProfileId()).thenReturn(8L);
        when(embeddings.embedText("인터넷이 느려요")).thenReturn(vector);
        when(vectors.findNearestClusterId(eq(RegionSido.BUSAN), eq(8L), eq(vector), eq(0.7), any()))
                .thenReturn(Optional.empty());
        when(vectors.createCluster(eq(RegionSido.BUSAN), eq("인터넷이 느려요"), eq(8L), eq(vector), any()))
                .thenReturn(12L);
        when(clusters.getReferenceById(12L)).thenReturn(mock(SurgingCluster.class));
        when(surgingQuestions.saveAndFlush(any())).thenReturn(saved);
        when(surgingQuestions.findSnapshotsByClusterIdAndOccurredAtAfter(eq(12L), any()))
                .thenReturn(List.of());

        service.registerQuestion(2L);

        verify(embeddings).embedText("인터넷이 느려요");
        verify(vectors).createCluster(eq(RegionSido.BUSAN), eq("인터넷이 느려요"), eq(8L), eq(vector), any());
    }

    @Test
    @DisplayName("잠금을 기다리는 동안 다른 작업이 등록한 질문은 중복 저장하지 않는다")
    void checksIdempotencyAgainAfterLock() {
        QuestionLog questionLog = questionLog(3L, RegionSido.SEOUL, "질문", "원문");
        when(surgingQuestions.existsByQuestionLogId(3L)).thenReturn(false, true);
        when(questionLogs.findById(3L)).thenReturn(Optional.of(questionLog));
        when(profiles.getCurrentProfileId()).thenReturn(1L);
        when(embeddings.embedText("질문")).thenReturn(new PGvector(new float[] {1}));

        service.registerQuestion(3L);

        verify(clusters).acquireRegionLock(anyLong());
        verifyNoInteractions(vectors, alerts, outbox);
        verify(surgingQuestions, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("임계값에 도달하면 클러스터를 선점하고 알림 제목 생성 이벤트를 발행한다")
    void createsAlertAtThreshold() {
        prepareStoredQuestion(4L, 14L, List.of(snapshot(1L), snapshot(2L), snapshot(3L)));
        when(clusters.claimAlert(14L)).thenReturn(1);
        SurgingAlert savedAlert = mock(SurgingAlert.class);
        when(savedAlert.getId()).thenReturn(44L);
        when(alerts.save(any(SurgingAlert.class))).thenReturn(savedAlert);

        service.registerQuestion(4L);

        verify(clusters).claimAlert(14L);
        verify(alerts).save(any(SurgingAlert.class));
        verify(outbox).enqueue(OutboxEventType.SURGING_ALERT_LABEL, "44", Map.of("alertId", 44L));
    }

    @Test
    @DisplayName("다른 작업이 알림을 선점했으면 중복 알림을 만들지 않는다")
    void doesNotCreateDuplicateAlertWhenClaimFails() {
        prepareStoredQuestion(5L, 15L, List.of(snapshot(1L), snapshot(2L), snapshot(3L)));
        when(clusters.claimAlert(15L)).thenReturn(0);

        service.registerQuestion(5L);

        verifyNoInteractions(alerts, outbox);
    }

    private void prepareStoredQuestion(long questionLogId, long clusterId,
            List<SurgingQuestionSnapshot> snapshots) {
        QuestionLog questionLog = questionLog(questionLogId, RegionSido.SEOUL, "질문", "원문");
        PGvector vector = new PGvector(new float[] {1});
        SurgingQuestion saved = mock(SurgingQuestion.class);
        when(saved.getId()).thenReturn(questionLogId + 100);
        when(questionLogs.findById(questionLogId)).thenReturn(Optional.of(questionLog));
        when(profiles.getCurrentProfileId()).thenReturn(1L);
        when(embeddings.embedText("질문")).thenReturn(vector);
        when(vectors.findNearestClusterId(eq(RegionSido.SEOUL), eq(1L), eq(vector), eq(0.7), any()))
                .thenReturn(Optional.of(clusterId));
        when(clusters.getReferenceById(clusterId)).thenReturn(mock(SurgingCluster.class));
        when(surgingQuestions.saveAndFlush(any())).thenReturn(saved);
        when(surgingQuestions.findSnapshotsByClusterIdAndOccurredAtAfter(eq(clusterId), any()))
                .thenReturn(snapshots);
    }

    private static QuestionLog questionLog(long id, RegionSido region, String subQuestion, String userQuestion) {
        QuestionLog questionLog = QuestionLog.builder()
                .userId(1L)
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
