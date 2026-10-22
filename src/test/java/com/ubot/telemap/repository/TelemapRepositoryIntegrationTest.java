package com.ubot.telemap.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "OUTBOX_POLL_DELAY_MS=3600000")
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("통신 지도 저장소 통합 테스트")
class TelemapRepositoryIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Autowired TelemapVectorRepository vectors;
    @Autowired SurgingClusterRepository clusters;
    @Autowired SurgingQuestionRepository questions;
    @Autowired SurgingAlertRepository alerts;
    @Autowired com.ubot.user.repository.UserRepository users;
    @Autowired com.ubot.notification.repository.NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean com.ubot.ranking.scheduler.RankingScheduler rankingScheduler;

    private long profileId;

    @BeforeEach
    void setUp() {
        profileId = jdbc.queryForObject("""
                INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
                VALUES ('openai-compatible', ?, 1024, 1)
                RETURNING profile_id
                """, Long.class, "telemap-test-" + System.nanoTime());
    }

    @Test
    @DisplayName("지역·프로필·보존 시간·유사도 조건을 만족하는 가장 가까운 클러스터를 찾는다")
    void findsNearestActiveCluster() {
        long nearest = vectors.createCluster(RegionSido.SEOUL, "가까운 질문", profileId,
                vector(1.0f, 0.0f), NOW.minusMinutes(5));
        vectors.createCluster(RegionSido.SEOUL, "먼 질문", profileId,
                vector(0.0f, 1.0f), NOW.minusMinutes(5));
        vectors.createCluster(RegionSido.BUSAN, "다른 지역", profileId,
                vector(1.0f, 0.0f), NOW.minusMinutes(5));
        vectors.createCluster(RegionSido.SEOUL, "만료", profileId,
                vector(1.0f, 0.0f), NOW.minusHours(2));

        assertThat(vectors.findNearestClusterId(
                RegionSido.SEOUL, profileId, vector(1.0f, 0.0f), 0.8, NOW.minusHours(1)))
                .contains(nearest);
        assertThat(vectors.findNearestClusterId(
                RegionSido.BUSAN, profileId, vector(0.0f, 1.0f), 0.8, NOW.minusHours(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("질문 임베딩 평균으로 중심 벡터를 갱신하고 마지막 발생 시각은 뒤로 가지 않는다")
    void updatesCentroidAndKeepsLatestOccurredAt() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);
        long first = createSurgingQuestion(clusterId, "첫 질문", NOW);
        long second = createSurgingQuestion(clusterId, "둘째 질문", NOW.minusMinutes(1));
        vectors.saveQuestionEmbedding(first, profileId, vector(1.0f, 0.0f));
        vectors.saveQuestionEmbedding(second, profileId, vector(0.0f, 1.0f));

        vectors.updateClusterCentroid(clusterId, profileId, NOW.minusMinutes(1));

        String centroid = jdbc.queryForObject(
                "SELECT centroid_embedding::text FROM surging_clusters WHERE id = ?", String.class, clusterId);
        LocalDateTime lastOccurredAt = jdbc.queryForObject(
                "SELECT last_occurred_at FROM surging_clusters WHERE id = ?", LocalDateTime.class, clusterId);
        assertThat(centroid).startsWith("[0.5,0.5,");
        assertThat(lastOccurredAt).isEqualTo(NOW);
    }

    @Test
    @DisplayName("질문 스냅샷은 시간 창 안의 질문을 발생 순서로 반환하고 null 하위 질문은 원문으로 대체한다")
    void returnsOrderedSnapshotsInWindow() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);
        long oldQuestionLogId = createQuestionLog("오래된 원문", "오래된 하위", NOW.minusMinutes(20));
        long firstQuestionLogId = createQuestionLog("첫 원문", null, NOW.minusMinutes(5));
        long secondQuestionLogId = createQuestionLog("둘째 원문", "둘째 하위", NOW.minusMinutes(4));
        insertSurgingQuestion(oldQuestionLogId, clusterId, NOW.minusMinutes(20));
        insertSurgingQuestion(firstQuestionLogId, clusterId, NOW.minusMinutes(5));
        insertSurgingQuestion(secondQuestionLogId, clusterId, NOW.minusMinutes(4));

        List<SurgingQuestionSnapshot> snapshots = questions.findSnapshotsByClusterIdAndOccurredAtAfter(
                clusterId, NOW.minusMinutes(10));

        assertThat(snapshots).extracting(SurgingQuestionSnapshot::questionLogId)
                .containsExactly(firstQuestionLogId, secondQuestionLogId);
        assertThat(snapshots).extracting(SurgingQuestionSnapshot::question)
                .containsExactly("첫 원문", "둘째 하위");
    }

    @Test
    @DisplayName("클러스터 알림 선점은 최초 한 번만 성공한다")
    void claimsClusterOnce() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);

        assertThat(clusters.claimAlert(clusterId)).isEqualTo(1);
        assertThat(clusters.claimAlert(clusterId)).isZero();
    }

    @Test
    @DisplayName("급증 알림은 상태와 지역을 필터링하고 최신순으로 반환한다")
    void filtersAndOrdersAlerts() {
        SurgingAlert first = alerts.saveAndFlush(alert(RegionSido.SEOUL, "첫 알림"));
        SurgingAlert second = alerts.saveAndFlush(alert(RegionSido.BUSAN, "둘째 알림"));
        org.springframework.test.util.ReflectionTestUtils.setField(first, "createdAt", NOW.minusMinutes(1));
        org.springframework.test.util.ReflectionTestUtils.setField(second, "createdAt", NOW);
        alerts.saveAndFlush(first);
        alerts.saveAndFlush(second);

        var result = alerts.findAllByFilters(
                SurgingAlertStatus.PENDING, RegionSido.SEOUL,
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(SurgingAlert::getId).containsExactly(first.getId());
    }

    @Test
    @DisplayName("대기 중 알림만 한 번 처리 완료할 수 있다")
    void marksPendingAlertProcessedOnce() {
        SurgingAlert alert = alerts.saveAndFlush(alert(RegionSido.SEOUL, "처리 대상"));
        Long administratorId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, role)
                VALUES (?, 'pw', '관리자', 'ADMIN') RETURNING user_id
                """, Long.class, "telemap-admin-" + System.nanoTime() + "@test.invalid");
        com.ubot.user.entity.User administrator = users.getReferenceById(administratorId);

        assertThat(alerts.markProcessedIfPending(alert.getId(), administrator, NOW)).isEqualTo(1);
        assertThat(alerts.markProcessedIfPending(alert.getId(), administrator, NOW.plusMinutes(1))).isZero();

        SurgingAlert reloaded = alerts.findById(alert.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SurgingAlertStatus.PROCESSED);
        assertThat(reloaded.getUpdatedBy().getId()).isEqualTo(administratorId);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("같은 질문 로그는 급증 질문으로 한 번만 저장할 수 있다")
    void enforcesUniqueQuestionLogRegistration() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);
        long questionLogId = createQuestionLog("원문", "하위", NOW);
        insertSurgingQuestion(questionLogId, clusterId, NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> insertSurgingQuestion(questionLogId, clusterId, NOW))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 급증 알림과 관리자의 알림은 dedupe key로 한 번만 저장한다")
    void deduplicatesAdministratorNotification() {
        SurgingAlert alert = alerts.saveAndFlush(alert(RegionSido.SEOUL, "급증 제목"));
        Long administratorId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, role)
                VALUES (?, 'pw', '관리자', 'ADMIN') RETURNING user_id
                """, Long.class, "notification-admin-" + System.nanoTime() + "@test.invalid");
        String dedupeKey = "SURGING_ALERT:" + alert.getId() + ":" + administratorId;

        int first = notifications.insertSurgingAlertIfAbsent(administratorId, "SURGING_ALERT", "제목", "본문",
                alert.getId(), dedupeKey, NOW);
        int second = notifications.insertSurgingAlertIfAbsent(administratorId, "SURGING_ALERT", "제목", "본문",
                alert.getId(), dedupeKey, NOW.plusSeconds(1));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE dedupe_key = ?", Long.class, dedupeKey)).isEqualTo(1L);
    }

    private long createSurgingQuestion(long clusterId, String text, LocalDateTime occurredAt) {
        long questionLogId = createQuestionLog(text, text, occurredAt);
        return insertSurgingQuestion(questionLogId, clusterId, occurredAt);
    }

    private long createQuestionLog(String userQuestion, String subQuestion, LocalDateTime createdAt) {
        Long conversationId = jdbc.queryForObject(
                "INSERT INTO conversations (type) VALUES ('GUEST') RETURNING conversation_id", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO question_log (
                    conversation_id, user_question, sub_question, normalized_question,
                    ranking_eligible, region, created_at
                ) VALUES (?, ?, ?, ?, true, 'SEOUL', ?) RETURNING id
                """, Long.class, conversationId, userQuestion, subQuestion, userQuestion, createdAt);
    }

    private long insertSurgingQuestion(long questionLogId, long clusterId, LocalDateTime occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO surging_questions (question_log_id, cluster_id, occurred_at)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, questionLogId, clusterId, occurredAt);
    }

    private static SurgingAlert alert(RegionSido region, String subject) {
        SurgingAlert alert = SurgingAlert.create(region,
                List.of(new SurgingQuestionSnapshot(1L, "질문", NOW)), 10);
        alert.updateSubject(subject);
        return alert;
    }

    private static PGvector vector(float first, float second) {
        float[] values = new float[1024];
        values[0] = first;
        values[1] = second;
        return new PGvector(values);
    }
}
