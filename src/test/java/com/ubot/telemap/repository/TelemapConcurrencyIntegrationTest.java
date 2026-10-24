package com.ubot.telemap.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.ranking.enums.RegionSido;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "OUTBOX_POLL_DELAY_MS=3600000")
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("통신 지도 동시성 통합 테스트")
class TelemapConcurrencyIntegrationTest {

    @Autowired TelemapVectorRepository vectors;
    @Autowired SurgingClusterRepository clusters;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @MockitoBean com.ubot.ranking.scheduler.RankingScheduler rankingScheduler;

    @Test
    @DisplayName("동시에 임계값을 넘겨도 한 작업만 클러스터 알림을 선점한다")
    void onlyOneTransactionClaimsAlert() throws Exception {
        long profileId = createProfile();
        long clusterId = vectors.createCluster(RegionSido.SEOUL, "대표 질문", profileId,
                vector(), LocalDateTime.now());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> claim(clusterId, ready, start));
            Future<Integer> second = executor.submit(() -> claim(clusterId, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        }
    }

    private int claim(long clusterId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return new TransactionTemplate(transactionManager).execute(status -> clusters.claimAlert(clusterId));
    }

    private long createProfile() {
        return jdbc.queryForObject("""
                INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
                VALUES ('openai-compatible', ?, 1024, 1) RETURNING profile_id
                """, Long.class, "telemap-concurrency-" + System.nanoTime());
    }

    private static PGvector vector() {
        float[] values = new float[1024];
        values[0] = 1;
        return new PGvector(values);
    }
}
