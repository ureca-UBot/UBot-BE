package com.ubot.telemap.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.entity.SurgingAlert;
import com.ubot.telemap.enums.SurgingAlertStatus;
import com.ubot.telemap.service.TelemapAlertService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
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
    @Autowired TelemapAlertService telemapAlertService;
    @Autowired TelemapRetentionRepository retention;
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
    @DisplayName("지역·프로필·유사도 조건을 만족하는 가장 가까운 클러스터를 찾는다")
    void findsNearestCluster() {
        long nearest = vectors.createCluster(RegionSido.SEOUL, "가까운 질문", profileId,
                vector(1.0f, 0.0f), NOW.minusMinutes(5));
        vectors.createCluster(RegionSido.SEOUL, "먼 질문", profileId,
                vector(0.0f, 1.0f), NOW.minusMinutes(5));
        vectors.createCluster(RegionSido.BUSAN, "다른 지역", profileId,
                vector(1.0f, 0.0f), NOW.minusMinutes(5));
        assertThat(vectors.findNearestClusterId(
                RegionSido.SEOUL, profileId, vector(1.0f, 0.0f), 0.8))
                .contains(nearest);
        assertThat(vectors.findNearestClusterId(
                RegionSido.BUSAN, profileId, vector(0.0f, 1.0f), 0.8))
                .isEmpty();
    }

    @Test
    @DisplayName("최신 질문 임베딩 10개의 평균으로 중심 벡터를 갱신하고 마지막 발생 시각은 뒤로 가지 않는다")
    void updatesCentroidWithLatestTenAndKeepsLatestOccurredAt() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW.minusMinutes(20));
        long oldQuestion = createSurgingQuestion(clusterId, "오래된 질문", NOW.minusMinutes(11));
        vectors.saveQuestionEmbedding(oldQuestion, profileId, vector(1.0f, 0.0f));
        for (int index = 0; index < 10; index++) {
            long recentQuestion = createSurgingQuestion(
                    clusterId, "최신 질문 " + index, NOW.minusMinutes(index));
            vectors.saveQuestionEmbedding(recentQuestion, profileId, vector(0.0f, 1.0f));
        }

        vectors.updateClusterCentroid(clusterId, profileId, NOW);

        String centroid = jdbc.queryForObject(
                "SELECT centroid_embedding::text FROM surging_clusters WHERE id = ?", String.class, clusterId);
        LocalDateTime lastOccurredAt = jdbc.queryForObject(
                "SELECT last_occurred_at FROM surging_clusters WHERE id = ?", LocalDateTime.class, clusterId);
        assertThat(centroid).startsWith("[0,1,");
        assertThat(lastOccurredAt).isEqualTo(NOW);
    }

    @Test
    @DisplayName("최근 질문 스냅샷은 시간 창 안에서 최신순으로 제한하고 null 하위 질문은 원문으로 대체한다")
    void returnsRecentSnapshotsInWindow() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);
        long oldQuestionLogId = createQuestionLog("오래된 원문", "오래된 하위", NOW.minusMinutes(20));
        long firstQuestionLogId = createQuestionLog("첫 원문", null, NOW.minusMinutes(5));
        long secondQuestionLogId = createQuestionLog("둘째 원문", "둘째 하위", NOW.minusMinutes(4));
        insertSurgingQuestion(oldQuestionLogId, clusterId, NOW.minusMinutes(20));
        insertSurgingQuestion(firstQuestionLogId, clusterId, NOW.minusMinutes(5));
        insertSurgingQuestion(secondQuestionLogId, clusterId, NOW.minusMinutes(4));

        List<SurgingQuestionSnapshot> snapshots = questions.findRecentSnapshotsByClusterIdAndOccurredAtBetween(
                clusterId, NOW.minusMinutes(10), NOW, PageRequest.of(0, 100));

        assertThat(snapshots).extracting(SurgingQuestionSnapshot::questionLogId)
                .containsExactly(secondQuestionLogId, firstQuestionLogId);
        assertThat(snapshots).extracting(SurgingQuestionSnapshot::question)
                .containsExactly("둘째 하위", "첫 원문");
        assertThat(questions.countByClusterIdAndOccurredAtBetween(
                clusterId, NOW.minusMinutes(10), NOW)).isEqualTo(2);
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
    @DisplayName("대기 중이고 클러스터가 연결된 알림만 누적 질문 수로 갱신하며 updated_at과 스냅샷은 바꾸지 않는다")
    void refreshesQuestionCountsOnlyForLinkedPendingAlerts() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);
        for (int i = 0; i < 3; i++) {
            insertSurgingQuestion(createQuestionLog("원문" + i, null, NOW.minusMinutes(i)), clusterId, NOW.minusMinutes(i));
        }
        long emptyClusterId = vectors.createCluster(RegionSido.SEOUL, "질문 없음", profileId,
                vector(0.0f, 1.0f), NOW);
        List<SurgingQuestionSnapshot> snapshots = List.of(new SurgingQuestionSnapshot(1L, "질문", NOW));
        SurgingAlert pending = alerts.saveAndFlush(SurgingAlert.create(RegionSido.SEOUL, clusterId, snapshots, 10, 10));
        SurgingAlert processed = alerts.saveAndFlush(SurgingAlert.create(RegionSido.SEOUL, clusterId, snapshots, 10, 10));
        SurgingAlert unlinked = alerts.saveAndFlush(SurgingAlert.create(RegionSido.SEOUL, snapshots, 10, 10));
        SurgingAlert emptyCluster = alerts.saveAndFlush(
                SurgingAlert.create(RegionSido.SEOUL, emptyClusterId, snapshots, 10, 10));
        jdbc.update("UPDATE surging_alerts SET status = 'PROCESSED' WHERE id = ?", processed.getId());
        LocalDateTime updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM surging_alerts WHERE id = ?", LocalDateTime.class, pending.getId());

        assertThat(alerts.refreshPendingQuestionCounts()).isEqualTo(1);
        assertThat(alerts.refreshPendingQuestionCounts()).isZero();

        assertThat(questionCount(pending)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT updated_at FROM surging_alerts WHERE id = ?", LocalDateTime.class, pending.getId()))
                .isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject(
                "SELECT jsonb_array_length(question_snapshots) FROM surging_alerts WHERE id = ?",
                Integer.class, pending.getId())).isEqualTo(1);
        assertThat(questionCount(processed)).isEqualTo(10);
        assertThat(questionCount(unlinked)).isEqualTo(10);
        assertThat(questionCount(emptyCluster)).isEqualTo(10);
    }

    @Test
    @DisplayName("연결된 클러스터가 정리되면 알림은 남고 cluster_id만 비워진다")
    void clearsClusterIdWhenClusterIsDeleted() {
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표", profileId,
                vector(1.0f, 0.0f), NOW);
        SurgingAlert alert = alerts.saveAndFlush(SurgingAlert.create(RegionSido.SEOUL, clusterId,
                List.of(new SurgingQuestionSnapshot(1L, "질문", NOW)), 10, 10));

        jdbc.update("DELETE FROM surging_clusters WHERE id = ?", clusterId);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM surging_alerts WHERE id = ?", Long.class, alert.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT cluster_id FROM surging_alerts WHERE id = ?", Long.class, alert.getId())).isNull();
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
    @DisplayName("대기 중인 알림을 처리하면 응답도 처리 완료 상태를 반환한다")
    void returnsProcessedAlertAfterMarkingItProcessed() {
        SurgingAlert alert = alerts.saveAndFlush(alert(RegionSido.SEOUL, "처리할 알림"));
        Long administratorId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, name, role)
                VALUES (?, 'pw', '관리자', 'ADMIN') RETURNING user_id
                """, Long.class, "response-admin-" + System.nanoTime() + "@test.invalid");

        var response = telemapAlertService.markProcessed(alert.getId(), administratorId);

        assertThat(response.status()).isEqualTo(SurgingAlertStatus.PROCESSED);
        assertThat(response.processedById()).isEqualTo(administratorId);
        assertThat(response.processedAt()).isNotNull();
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

    @Test
    @DisplayName("스냅샷 텍스트는 알림 생성 시각이 아니라 각 질문 발생 시각을 기준으로 익명화한다")
    void anonymizesOnlySnapshotsPastTheOccurrenceCutoff() {
        LocalDateTime cutoff = NOW.minusMonths(3);
        SurgingAlert alert = alerts.saveAndFlush(SurgingAlert.create(
                RegionSido.SEOUL,
                List.of(
                        new SurgingQuestionSnapshot(1L, "오래된 질문", cutoff.minusSeconds(1)),
                        new SurgingQuestionSnapshot(2L, "최근 질문", cutoff.plusSeconds(1))
                ),
                2,
                10
        ));

        assertThat(retention.anonymizeAlertSnapshots(cutoff)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT question_snapshots -> 0 ->> 'question' FROM surging_alerts WHERE id = ?",
                String.class,
                alert.getId()
        )).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT question_snapshots -> 1 ->> 'question' FROM surging_alerts WHERE id = ?",
                String.class,
                alert.getId()
        )).isEqualTo("최근 질문");
    }

    @Test
    @DisplayName("생성 후 하루가 지나고 최근 10분간 질문이 없는 클러스터만 연관 데이터와 함께 삭제한다")
    void deletesOnlyOldInactiveClustersWithChildren() {
        long expiredClusterId = vectors.createCluster(
                RegionSido.SEOUL, "삭제 대상", profileId, vector(1.0f, 0.0f), NOW.minusMinutes(11));
        long youngClusterId = vectors.createCluster(
                RegionSido.SEOUL, "생성된 지 하루 미만", profileId, vector(1.0f, 0.0f), NOW.minusMinutes(11));
        long activeClusterId = vectors.createCluster(
                RegionSido.SEOUL, "최근 질문 존재", profileId, vector(1.0f, 0.0f), NOW.minusMinutes(9));
        jdbc.update("UPDATE surging_clusters SET created_at = ? WHERE id = ?", NOW.minusDays(2), expiredClusterId);
        jdbc.update("UPDATE surging_clusters SET created_at = ? WHERE id = ?", NOW.minusHours(23), youngClusterId);
        jdbc.update("UPDATE surging_clusters SET created_at = ? WHERE id = ?", NOW.minusDays(2), activeClusterId);
        long questionId = createSurgingQuestion(expiredClusterId, "삭제될 질문", NOW.minusMinutes(11));
        vectors.saveQuestionEmbedding(questionId, profileId, vector(1.0f, 0.0f));

        assertThat(retention.deleteExpiredClusters(NOW.minusDays(1), NOW.minusMinutes(10))).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM surging_clusters WHERE id = ?", Long.class, expiredClusterId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM surging_questions WHERE id = ?", Long.class, questionId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM surging_question_embeddings WHERE question_id = ?", Long.class, questionId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM surging_clusters WHERE id IN (?, ?)",
                Long.class,
                youngClusterId,
                activeClusterId
        )).isEqualTo(2L);
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

    private Integer questionCount(SurgingAlert alert) {
        return jdbc.queryForObject(
                "SELECT question_count FROM surging_alerts WHERE id = ?", Integer.class, alert.getId());
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
