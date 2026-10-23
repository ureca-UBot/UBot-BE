package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.home.entity.HomeSubscription;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.enums.HomeSubscriptionStatus;
import com.ubot.subscription.entity.Subscription;
import com.ubot.subscription.entity.SubscriptionBundle;
import com.ubot.subscription.enums.BundleGroupStatus;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("상품 카탈로그 확장 컬럼·테이블의 entity·DTO 반영 통합 테스트")
class ProductCatalogFieldIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private User administrator;

    @BeforeEach
    void createAdministrator() {
        LocalDateTime now = LocalDateTime.now();
        administrator = userRepository.saveAndFlush(User.builder()
                .email("catalog-field-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only").name("상품 관리자").role(UserRole.ADMIN)
                .createdAt(now).updatedAt(now).build());
    }

    @Test
    @DisplayName("시드 상품 조회 응답에 결합 종류·회선 수·인터넷 상품과 부가서비스 분류가 담긴다")
    void exposesSeededFields() throws Exception {
        mockMvc.perform(get("/bundle-products/" + id("bundle_products", "bundle_product_id", "UBOT_BUNDLE_FAMILY_3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bundleType").value("FAMILY"))
                .andExpect(jsonPath("$.data.lineCount").value(3))
                .andExpect(jsonPath("$.data.homeProductId").doesNotExist());
        mockMvc.perform(get("/bundle-products/" + id("bundle_products", "bundle_product_id", "UBOT_BUNDLE_HOME_500")))
                .andExpect(jsonPath("$.data.bundleType").value("HOME"))
                .andExpect(jsonPath("$.data.lineCount").value(1))
                .andExpect(jsonPath("$.data.homeProductId")
                        .value(id("home_products", "home_product_id", "UBOT_HOME_INTERNET_500")));
        mockMvc.perform(get("/addon-services/" + id("addon_services", "addon_service_id", "UBOT_ADDON_MUSIC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("CONTENT"));
    }

    @Test
    @DisplayName("결합 상품 등록 시 새 필드를 비우면 ETC·제한 없음이고, 수정 시 비우면 기존 값을 유지한다")
    void createsAndUpdatesBundleFields() throws Exception {
        long internetId = id("home_products", "home_product_id", "UBOT_HOME_INTERNET_100");
        long homeId = createdId(send(post("/admin/bundle-products"), """
                {"code":"FIELD_HOME","name":"홈 결합","discountAmount":3300,
                 "bundleType":"HOME","lineCount":1,"homeProductId":%d}
                """.formatted(internetId)), "bundleProductId");
        long plainId = createdId(send(post("/admin/bundle-products"), """
                {"code":"FIELD_PLAIN","name":"기본 결합","discountAmount":1000}
                """), "bundleProductId");

        send(put("/admin/bundle-products/" + homeId), """
                {"name":"홈 결합 수정","discountAmount":3300}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.bundleType").value("HOME"))
                .andExpect(jsonPath("$.data.detail.lineCount").value(1))
                .andExpect(jsonPath("$.data.detail.homeProductId").value(internetId));
        mockMvc.perform(get("/admin/bundle-products/" + plainId).with(user(new CustomUserDetails(administrator))))
                .andExpect(jsonPath("$.data.detail.bundleType").value("ETC"))
                .andExpect(jsonPath("$.data.detail.lineCount").doesNotExist())
                .andExpect(jsonPath("$.data.detail.homeProductId").doesNotExist());
    }

    @Test
    @DisplayName("홈 결합에는 인터넷 상품만 연결하고 회선 수는 1~10이다")
    void rejectsInvalidBundleFields() throws Exception {
        long iptvId = id("home_products", "home_product_id", "UBOT_HOME_IPTV_BASIC");
        send(post("/admin/bundle-products"), """
                {"code":"FIELD_IPTV","name":"IPTV 결합","discountAmount":1000,"homeProductId":%d}
                """.formatted(iptvId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUNDLE-003"));
        send(post("/admin/bundle-products"), """
                {"code":"FIELD_LINES","name":"회선 초과","discountAmount":1000,"lineCount":11}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("홈 결합은 인터넷 상품 없이 등록하거나 수정할 수 없다")
    void requiresInternetProductForHomeBundle() throws Exception {
        send(post("/admin/bundle-products"), """
                {"code":"FIELD_HOME_EMPTY","name":"인터넷 없는 홈 결합","discountAmount":1000,"bundleType":"HOME"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUNDLE-003"));
        long plainId = createdId(send(post("/admin/bundle-products"), """
                {"code":"FIELD_TO_HOME","name":"기본 결합","discountAmount":1000}
                """), "bundleProductId");
        send(put("/admin/bundle-products/" + plainId), """
                {"name":"기본 결합","discountAmount":1000,"bundleType":"HOME"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUNDLE-003"));
    }

    @Test
    @DisplayName("가입 이력이 있는 결합 상품은 결합 종류를 바꿀 수 없다")
    void protectsBundleTypeInUse() throws Exception {
        long bundleId = createdId(send(post("/admin/bundle-products"), """
                {"code":"FIELD_TYPE","name":"가족 결합","discountAmount":1000,"bundleType":"FAMILY","lineCount":2}
                """), "bundleProductId");
        jdbc.update("""
                INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at,
                    product_name_snapshot, applied_discount_amount)
                VALUES (?, ?, CURRENT_TIMESTAMP, '가족 결합', 1000)
                """, insertSubscription(), bundleId);
        send(put("/admin/bundle-products/" + bundleId), """
                {"name":"가족 결합","discountAmount":1000,"bundleType":"DUO"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUNDLE-004"));
        send(put("/admin/bundle-products/" + bundleId), """
                {"name":"가족 결합 이름만 수정","discountAmount":1000,"bundleType":"FAMILY"}
                """)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("부가서비스 분류는 비우면 ETC로 등록하고, 수정 시 비우면 기존 값을 유지한다")
    void createsAndUpdatesAddonCategory() throws Exception {
        long addonId = createdId(send(post("/admin/addon-services"), """
                {"code":"FIELD_ADDON","name":"분류 부가서비스","monthlyFee":1000,"category":"SAFETY"}
                """), "addonServiceId");
        send(put("/admin/addon-services/" + addonId), """
                {"name":"분류 부가서비스 수정","monthlyFee":1000}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.category").value("SAFETY"));
        long plainId = createdId(send(post("/admin/addon-services"), """
                {"code":"FIELD_ADDON_PLAIN","name":"기본 부가서비스","monthlyFee":0}
                """), "addonServiceId");
        mockMvc.perform(get("/addon-services/" + plainId))
                .andExpect(jsonPath("$.data.category").value("ETC"));
    }

    @Test
    @DisplayName("가입 이력이 있는 홈 결합이 연결한 인터넷 상품은 조건을 바꿀 수 없다")
    void protectsInternetProductOfSubscribedHomeBundle() throws Exception {
        long internetId = createdId(send(post("/admin/home-products"), """
                {"code":"FIELD_INTERNET","name":"결합 인터넷","productType":"INTERNET","speedMbps":100,"monthlyFee":22000}
                """), "homeProductId");
        long bundleId = createdId(send(post("/admin/bundle-products"), """
                {"code":"FIELD_HOME_LINKED","name":"홈 결합","discountAmount":3300,
                 "bundleType":"HOME","lineCount":1,"homeProductId":%d}
                """.formatted(internetId)), "bundleProductId");
        // 결합 가입 이력이 없으면 연결만 되어 있어도 조건을 바꿀 수 있다.
        send(put("/admin/home-products/" + internetId), """
                {"name":"결합 인터넷","speedMbps":500,"monthlyFee":33000}
                """)
                .andExpect(status().isOk());

        jdbc.update("""
                INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at,
                    product_name_snapshot, applied_discount_amount)
                VALUES (?, ?, CURRENT_TIMESTAMP, '홈 결합', 3300)
                """, insertSubscription(), bundleId);
        send(put("/admin/home-products/" + internetId), """
                {"name":"결합 인터넷","speedMbps":1000,"monthlyFee":44000}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOME-004"));
    }

    @Test
    @DisplayName("새 entity와 컬럼이 테이블에 매핑된다")
    void mapsNewEntities() {
        long userId = administrator.getId();
        long planId = id("plans", "plan_id", "UBOT_BALANCE_05", "plan_code");
        long subscriptionId = jdbc.queryForObject("""
                INSERT INTO subscriptions (user_id, plan_id, phone_number, activated_at, plan_changed_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, TIMESTAMP '2026-10-01 10:00:00') RETURNING subscription_id
                """, Long.class, userId, planId, "019" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000));
        long groupId = jdbc.queryForObject("""
                INSERT INTO bundle_groups (representative_subscription_id) VALUES (?) RETURNING bundle_group_id
                """, Long.class, subscriptionId);
        long subscriptionBundleId = jdbc.queryForObject("""
                INSERT INTO subscription_bundles (subscription_id, bundle_product_id, bundle_group_id, joined_at,
                    product_name_snapshot, applied_discount_amount)
                SELECT ?, bundle_product_id, ?, CURRENT_TIMESTAMP, name, discount_amount
                FROM bundle_products WHERE code = 'UBOT_BUNDLE_HOME_100' RETURNING subscription_bundle_id
                """, Long.class, subscriptionId, groupId);
        long homeSubscriptionId = jdbc.queryForObject("""
                INSERT INTO home_subscriptions (user_id, home_product_id, product_type, joined_at,
                    product_name_snapshot, monthly_fee_snapshot)
                SELECT ?, home_product_id, product_type, CURRENT_TIMESTAMP, name, monthly_fee
                FROM home_products WHERE code = 'UBOT_HOME_INTERNET_100' RETURNING home_subscription_id
                """, Long.class, userId);
        entityManager.clear();

        assertThat(entityManager.find(Subscription.class, subscriptionId).getPlanChangedAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 1, 10, 0));
        SubscriptionBundle bundle = entityManager.find(SubscriptionBundle.class, subscriptionBundleId);
        assertThat(bundle.getBundleGroup().getStatus()).isEqualTo(BundleGroupStatus.ACTIVE);
        assertThat(bundle.getBundleGroup().getRepresentativeSubscription().getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(bundle.getBundleProduct().getHomeProduct().getSpeedMbps()).isEqualTo(100);
        HomeSubscription home = entityManager.find(HomeSubscription.class, homeSubscriptionId);
        assertThat(home.getStatus()).isEqualTo(HomeSubscriptionStatus.ACTIVE);
        assertThat(home.getProductType()).isEqualTo(HomeProductType.INTERNET);
        assertThat(home.getHomeProduct().getMonthlyFee()).isEqualTo(22000);
        assertThat(home.getUser().getId()).isEqualTo(userId);
    }

    private long insertSubscription() {
        return jdbc.queryForObject("""
                INSERT INTO subscriptions (user_id, plan_id, phone_number, activated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP) RETURNING subscription_id
                """, Long.class, administrator.getId(), id("plans", "plan_id", "UBOT_BALANCE_05", "plan_code"),
                "019" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(user(new CustomUserDetails(administrator)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createdId(ResultActions result, String idField) throws Exception {
        String response = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"" + idField + "\":(\\d+).*", "$1"));
    }

    private long id(String table, String idColumn, String code) {
        return id(table, idColumn, code, "code");
    }

    private long id(String table, String idColumn, String code, String codeColumn) {
        return jdbc.queryForObject("SELECT " + idColumn + " FROM " + table + " WHERE " + codeColumn + " = ?",
                Long.class, code);
    }
}
