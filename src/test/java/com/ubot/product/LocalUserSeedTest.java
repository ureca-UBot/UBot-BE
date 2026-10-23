package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("로컬 초기화 사용자 시드(tools/faq-db/seed-demo-users.sql, seed-users.sql)")
class LocalUserSeedTest {

    private static final String USERS = "email LIKE 'user%@user.com'";

    @Test
    @DisplayName("가상 사용자 300명과 user 계정 10명이 가입 상품·이용 기록·결합 그룹을 빠짐없이 가진다")
    void seedsUsersWithSubscriptions() throws Exception {
        var image = new ImageFromDockerfile("ubot-postgres:18-pgvector0.8.6-postgis3.6.4", false)
                .withFileFromClasspath("Dockerfile", "Dockerfile");
        var imageName = DockerImageName.parse(image.get()).asCompatibleSubstituteFor("postgres");
        try (var postgres = new PostgreSQLContainer(imageName)
                .withDatabaseName("local_user_seed")
                .withUsername("seed_test")
                .withPassword(UUID.randomUUID().toString())
                .withLabel("com.ubot.test-db", "true")
                .withReuse(false)) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

            // 상품 시드(V47): 홈 결합은 모두 같은 속도의 인터넷 상품에 연결된다.
            assertThat(jdbc.queryForList("""
                    SELECT b.code FROM bundle_products b
                    LEFT JOIN home_products h ON h.home_product_id = b.home_product_id
                    WHERE b.bundle_type = 'HOME'
                      AND (h.home_product_id IS NULL OR h.product_type <> 'INTERNET'
                           OR b.name NOT LIKE '%' || CASE h.speed_mbps WHEN 1000 THEN '기가' ELSE h.speed_mbps::TEXT END || '%')
                    """, String.class)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM home_products", Integer.class)).isEqualTo(5);

            // reset-local-db.ps1과 같은 순서로 실행한다. seed-baseline.sql이 만드는 기본 사용자를 먼저 넣는다.
            jdbc.update("INSERT INTO users (email, password_hash, name) VALUES ('user@user.com', 'hash', 'Default User')");
            jdbc.execute(Files.readString(Path.of("tools/faq-db/seed-demo-users.sql")));
            jdbc.execute(Files.readString(Path.of("tools/faq-db/seed-users.sql")));

            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(310);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE " + USERS, Integer.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions", Integer.class)).isEqualTo(370 + 12);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_records", Integer.class)).isEqualTo(1440 + 48);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_records", Integer.class)).isEqualTo(1100 + 36);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_contracts", Integer.class)).isEqualTo(101 + 5);
            // 따로 가입 637 + 14, 요금제 포함(PLAN_INCLUDED) 36
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_addons", Integer.class)).isEqualTo(637 + 14 + 36);
            // 해지되지 않은 회선은 요금제에 포함된 부가서비스를 모두 0원 PLAN_INCLUDED로 이용 중이다.
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM subscriptions s
                    JOIN plan_included_addons pia ON pia.plan_id = s.plan_id
                    WHERE s.status <> 'TERMINATED'
                      AND NOT EXISTS (SELECT 1 FROM subscription_addons sa
                                      WHERE sa.subscription_id = s.subscription_id
                                        AND sa.addon_service_id = pia.addon_service_id
                                        AND sa.status = 'ACTIVE' AND sa.source = 'PLAN_INCLUDED'
                                        AND sa.monthly_fee_snapshot = 0)
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_roamings", Integer.class)).isEqualTo(80 + 3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_bundles", Integer.class)).isEqualTo(125 + 12);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM home_subscriptions", Integer.class)).isEqualTo(4);
            // 가상 사용자: 친구 3인 15개, 워치 20개, 태블릿 20개 / user 계정: 6개
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_groups", Integer.class)).isEqualTo(55 + 6);
            // 결합은 본인 명의 회선만 묶는다. 다른 사람 회선이 섞인 친구 3인 15개와 user 계정 3개(가족·청년·친구)는 종료된다.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_groups WHERE status = 'ACTIVE'", Integer.class))
                    .isEqualTo(55 + 6 - 15 - 3);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT sb.bundle_group_id FROM subscription_bundles sb
                        JOIN subscriptions s ON s.subscription_id = sb.subscription_id
                        WHERE sb.status = 'ACTIVE'
                        GROUP BY sb.bundle_group_id HAVING count(DISTINCT s.user_id) > 1) t
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM bundle_groups g
                    WHERE g.status = 'ENDED' AND EXISTS (SELECT 1 FROM subscription_bundles sb
                        WHERE sb.bundle_group_id = g.bundle_group_id AND sb.status = 'ACTIVE')
                    """, Integer.class)).isZero();

            // user 계정은 모두 회선·부가서비스·결합·사용량·청구를 가진 채 시작한다.
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM users u
                    WHERE u.%s
                      AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = u.user_id)
                      AND EXISTS (SELECT 1 FROM subscriptions s JOIN subscription_addons a
                                  ON a.subscription_id = s.subscription_id WHERE s.user_id = u.user_id)
                      AND EXISTS (SELECT 1 FROM subscriptions s JOIN subscription_bundles b
                                  ON b.subscription_id = s.subscription_id WHERE s.user_id = u.user_id)
                      AND EXISTS (SELECT 1 FROM subscriptions s JOIN usage_records r
                                  ON r.subscription_id = s.subscription_id WHERE s.user_id = u.user_id)
                      AND EXISTS (SELECT 1 FROM subscriptions s JOIN billing_records r
                                  ON r.subscription_id = s.subscription_id WHERE s.user_id = u.user_id)
                    """.formatted(USERS), Integer.class)).isEqualTo(10);
            // user 계정 청구서의 총액은 항목 합계와 같고, 미납·연체가 하나씩 있다.
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM billing_records r
                    JOIN subscriptions s ON s.subscription_id = r.subscription_id
                    JOIN users u ON u.user_id = s.user_id AND u.%s
                    WHERE r.total_amount <> r.base_fee + r.addon_fee + r.roaming_fee + r.extra_fee - r.discount_amount
                    """.formatted(USERS), Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM billing_records r
                    JOIN subscriptions s ON s.subscription_id = r.subscription_id
                    JOIN users u ON u.user_id = s.user_id AND u.%s
                    WHERE r.payment_status <> 'PAID'
                    """.formatted(USERS), Integer.class)).isEqualTo(2);

            // 청구 월은 청구서가 나온 달(생성일의 달)이고, 그 전월 사용량 기록이 있다(feat/160 기준).
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM billing_records r
                    WHERE r.billing_month <> date_trunc('month', r.created_at)::DATE
                       OR NOT EXISTS (SELECT 1 FROM usage_records u WHERE u.subscription_id = r.subscription_id
                                      AND u.billing_month = (r.billing_month - INTERVAL '1 month')::DATE)
                    """, Integer.class)).isZero();

            // 이용 중인 결합 기록은 모두 그룹에 속하고, 그룹의 회선 수와 할인액은 결합 상품과 같다.
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM subscription_bundles WHERE status = 'ACTIVE' AND bundle_group_id IS NULL",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForList("""
                    SELECT g.bundle_group_id FROM bundle_groups g
                    JOIN subscription_bundles sb ON sb.bundle_group_id = g.bundle_group_id
                    JOIN bundle_products b ON b.bundle_product_id = sb.bundle_product_id
                    GROUP BY g.bundle_group_id, b.line_count, b.discount_amount
                    HAVING count(*) <> b.line_count
                        OR sum(sb.applied_discount_amount) <> b.discount_amount
                        OR sum(CASE WHEN sb.subscription_id = g.representative_subscription_id
                                    THEN sb.applied_discount_amount ELSE 0 END) <> b.discount_amount
                    """, Long.class)).isEmpty();
        }
    }
}
