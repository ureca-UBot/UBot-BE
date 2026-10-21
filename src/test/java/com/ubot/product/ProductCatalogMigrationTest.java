package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("상품 카탈로그·가입 테이블 추가(V42)")
class ProductCatalogMigrationTest {

    @Test
    @DisplayName("기존 상품·가입 행은 기본값을 받고, 새 컬럼·테이블은 조건·유일성·FK 동작을 지킨다")
    void migratesExistingRowsAndAddsTables() {
        var image = new ImageFromDockerfile("ubot-postgres:18-pgvector0.8.6-postgis3.6.4", false)
                .withFileFromClasspath("Dockerfile", "Dockerfile");
        var imageName = DockerImageName.parse(image.get()).asCompatibleSubstituteFor("postgres");
        try (var postgres = new PostgreSQLContainer(imageName)
                .withDatabaseName("product_catalog_migration")
                .withUsername("migration_test")
                .withPassword(UUID.randomUUID().toString())
                .withLabel("com.ubot.test-db", "true")
                .withReuse(false)) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("41").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

            // V42 이전 형식의 상품과 가입 기록입니다.
            Long userId = jdbc.queryForObject("""
                    INSERT INTO users (email, password_hash, name)
                    VALUES ('catalog-migration@test.com', 'hash', '상품 확장') RETURNING user_id
                    """, Long.class);
            Long planId = jdbc.queryForObject("""
                    INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee,
                        data_amount_mb, voice_minutes, sms_count)
                    VALUES ('LEGACY_PLAN', '기존 요금제', 'FIVE_G', 'GENERAL', 50000, 1000, 100, 100)
                    RETURNING plan_id
                    """, Long.class);
            Long bundleId = jdbc.queryForObject("""
                    INSERT INTO bundle_products (code, name, discount_amount) VALUES ('LEGACY_BUNDLE', '기존 결합', 5000)
                    RETURNING bundle_product_id
                    """, Long.class);
            Long addonId = jdbc.queryForObject("""
                    INSERT INTO addon_services (code, name, monthly_fee) VALUES ('LEGACY_ADDON', '기존 부가서비스', 1000)
                    RETURNING addon_service_id
                    """, Long.class);
            Long subscriptionId = jdbc.queryForObject("""
                    INSERT INTO subscriptions (user_id, plan_id, phone_number, activated_at)
                    VALUES (?, ?, '01012345678', CURRENT_TIMESTAMP) RETURNING subscription_id
                    """, Long.class, userId, planId);
            Long subscriptionBundleId = jdbc.queryForObject("""
                    INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at,
                        product_name_snapshot, applied_discount_amount)
                    VALUES (?, ?, CURRENT_TIMESTAMP, '기존 결합', 5000) RETURNING subscription_bundle_id
                    """, Long.class, subscriptionId, bundleId);

            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("42").load().migrate();

            // 추가 컬럼: 기존 행은 기본값 또는 NULL을 받는다.
            assertThat(jdbc.queryForMap(
                    "SELECT bundle_type, line_count, home_product_id FROM bundle_products WHERE bundle_product_id = ?",
                    bundleId))
                    .containsEntry("bundle_type", "ETC")
                    .containsEntry("line_count", null)
                    .containsEntry("home_product_id", null);
            assertThat(jdbc.queryForObject("SELECT category FROM addon_services WHERE addon_service_id = ?",
                    String.class, addonId)).isEqualTo("ETC");
            assertThat(jdbc.queryForObject("SELECT plan_changed_at FROM subscriptions WHERE subscription_id = ?",
                    Object.class, subscriptionId)).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT bundle_group_id FROM subscription_bundles WHERE subscription_bundle_id = ?",
                    Object.class, subscriptionBundleId)).isNull();
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE bundle_products SET bundle_type = 'UNKNOWN' WHERE bundle_product_id = ?", bundleId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE addon_services SET category = 'UNKNOWN' WHERE addon_service_id = ?", addonId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // home_products: 인터넷은 속도가 있어야 하고 IPTV는 속도가 없어야 한다.
            Long internetId = jdbc.queryForObject("""
                    INSERT INTO home_products (code, name, product_type, speed_mbps, monthly_fee)
                    VALUES ('INTERNET_TEST', '인터넷', 'INTERNET', 100, 22000) RETURNING home_product_id
                    """, Long.class);
            Long iptvId = jdbc.queryForObject("""
                    INSERT INTO home_products (code, name, product_type, monthly_fee)
                    VALUES ('IPTV_TEST', 'IPTV', 'IPTV', 16500) RETURNING home_product_id
                    """, Long.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO home_products (code, name, product_type, monthly_fee)
                    VALUES ('INTERNET_NO_SPEED', '속도 없는 인터넷', 'INTERNET', 22000)
                    """)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO home_products (code, name, product_type, speed_mbps, monthly_fee)
                    VALUES ('IPTV_SPEED', '속도 있는 IPTV', 'IPTV', 100, 16500)
                    """)).isInstanceOf(DataIntegrityViolationException.class);

            // bundle_products: 결합 회선 수는 1~10, 홈 결합은 인터넷 상품을 가리킨다.
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE bundle_products SET line_count = 0 WHERE bundle_product_id = ?", bundleId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Long homeBundleId = jdbc.queryForObject("""
                    INSERT INTO bundle_products (code, name, discount_amount, bundle_type, line_count, home_product_id)
                    VALUES ('HOME_TEST', '홈 결합', 3300, 'HOME', 1, ?) RETURNING bundle_product_id
                    """, Long.class, internetId);

            // plan_included_addons: 같은 연결은 한 번만.
            jdbc.update("INSERT INTO plan_included_addons (plan_id, addon_service_id) VALUES (?, ?)", planId, addonId);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO plan_included_addons (plan_id, addon_service_id) VALUES (?, ?)", planId, addonId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // home_subscriptions: 종류별로 이용 중인 가입은 하나, 종료하면 종료 시각이 필요하다.
            Long internetSubscriptionId = homeSubscription(jdbc, userId, internetId, "INTERNET");
            homeSubscription(jdbc, userId, iptvId, "IPTV");
            assertThatThrownBy(() -> homeSubscription(jdbc, userId, internetId, "INTERNET"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE home_subscriptions SET status = 'ENDED' WHERE home_subscription_id = ?",
                    internetSubscriptionId)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("""
                    UPDATE home_subscriptions SET status = 'ENDED', ended_at = joined_at WHERE home_subscription_id = ?
                    """, internetSubscriptionId);
            homeSubscription(jdbc, userId, internetId, "INTERNET");

            // bundle_groups: 이용 중인 그룹은 대표 회선이 있어야 하고, 결합 기록에 그룹을 연결한다.
            assertThatThrownBy(() -> jdbc.update("INSERT INTO bundle_groups (status) VALUES ('ACTIVE')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Long groupId = jdbc.queryForObject("""
                    INSERT INTO bundle_groups (representative_subscription_id) VALUES (?) RETURNING bundle_group_id
                    """, Long.class, subscriptionId);
            jdbc.update("UPDATE subscription_bundles SET bundle_group_id = ? WHERE subscription_bundle_id = ?",
                    groupId, subscriptionBundleId);

            // 정리(purge) 배치를 막지 않도록: 상품이 지워지면 연결·조건이 함께 지워지고, 대표 회선은 비워진다.
            jdbc.update("DELETE FROM subscription_bundles WHERE subscription_bundle_id = ?", subscriptionBundleId);
            jdbc.update("UPDATE bundle_groups SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE bundle_group_id = ?",
                    groupId);
            jdbc.update("DELETE FROM subscriptions WHERE subscription_id = ?", subscriptionId);
            assertThat(jdbc.queryForObject(
                    "SELECT representative_subscription_id FROM bundle_groups WHERE bundle_group_id = ?",
                    Object.class, groupId)).isNull();
            jdbc.update("DELETE FROM plans WHERE plan_id = ?", planId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_included_addons", Integer.class)).isZero();

            // 회원이 지워지면 인터넷/IPTV 가입도 함께 지워지고, 인터넷 상품이 지워지면 홈 결합의 연결만 비워진다.
            jdbc.update("DELETE FROM users WHERE user_id = ?", userId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM home_subscriptions", Integer.class)).isZero();
            jdbc.update("DELETE FROM home_products WHERE home_product_id = ?", internetId);
            assertThat(jdbc.queryForObject("SELECT home_product_id FROM bundle_products WHERE bundle_product_id = ?",
                    Object.class, homeBundleId)).isNull();
        }
    }

    private Long homeSubscription(JdbcTemplate jdbc, Long userId, Long homeProductId, String productType) {
        return jdbc.queryForObject("""
                INSERT INTO home_subscriptions (user_id, home_product_id, product_type, joined_at,
                    product_name_snapshot, monthly_fee_snapshot)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, '인터넷/IPTV', 10000) RETURNING home_subscription_id
                """, Long.class, userId, homeProductId, productType);
    }
}
