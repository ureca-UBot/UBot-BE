package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("로컬 초기화 사용자 시드(tools/faq-db/seed-users.sql)")
class LocalUserSeedTest {

    @Test
    @DisplayName("상품 카탈로그를 코드로 찾아 사용자 10명의 회선·부가서비스·결합·로밍·인터넷/IPTV를 빠짐없이 만든다")
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
            var dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            // 상품 시드(V43): 홈 결합은 모두 같은 속도의 인터넷 상품에 연결된다.
            assertThat(jdbc.queryForList("""
                    SELECT b.code FROM bundle_products b
                    LEFT JOIN home_products h ON h.home_product_id = b.home_product_id
                    WHERE b.bundle_type = 'HOME'
                      AND (h.home_product_id IS NULL OR h.product_type <> 'INTERNET'
                           OR b.name NOT LIKE '%' || CASE h.speed_mbps WHEN 1000 THEN '기가' ELSE h.speed_mbps::TEXT END || '%')
                    """, String.class)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM home_products", Integer.class)).isEqualTo(5);
            // seed-baseline.sql이 만드는 기본 사용자입니다.
            jdbc.update("INSERT INTO users (email, password_hash, name) VALUES ('user@user.com', 'hash', 'Default User')");

            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("tools/faq-db/seed-users.sql"));
            }

            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email LIKE 'user%@user.com'", Integer.class))
                    .isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions", Integer.class)).isEqualTo(12);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_addons", Integer.class)).isEqualTo(14);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM home_subscriptions", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_roamings", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bundle_groups", Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_bundles", Integer.class)).isEqualTo(12);

            // 모든 사용자가 회선과 부가서비스와 결합을 가진 채 시작한다.
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM users u
                    WHERE u.email LIKE 'user%@user.com'
                      AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = u.user_id)
                      AND EXISTS (SELECT 1 FROM subscriptions s JOIN subscription_addons a
                                  ON a.subscription_id = s.subscription_id WHERE s.user_id = u.user_id)
                      AND EXISTS (SELECT 1 FROM subscriptions s JOIN subscription_bundles b
                                  ON b.subscription_id = s.subscription_id WHERE s.user_id = u.user_id)
                    """, Integer.class)).isEqualTo(10);
            // 결합 그룹의 회선 수는 결합 상품의 회선 수와 같고, 할인액은 대표 회선에만 있다.
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
