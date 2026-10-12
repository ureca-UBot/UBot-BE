package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.enums.MasterStatus;
import com.ubot.product.ProductCrudIntegrationTest.Product;
import com.ubot.subscription.entity.Subscription;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("가입 이력이 있는 상품의 조건 보존 통합 테스트")
class ProductSubscriptionProtectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private User administrator;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        administrator = userRepository.saveAndFlush(User.builder()
                .email("product-history-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only")
                .name("가입 이력 관리자")
                .role(UserRole.ADMIN)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    static Stream<Arguments> histories() {
        return Stream.of(
                Arguments.of(Product.PLAN, "ACTIVE"),
                Arguments.of(Product.PLAN, "SUSPENDED"),
                Arguments.of(Product.PLAN, "TERMINATED"),
                Arguments.of(Product.BUNDLE, "ACTIVE"),
                Arguments.of(Product.BUNDLE, "ENDED"),
                Arguments.of(Product.ADDON, "ACTIVE"),
                Arguments.of(Product.ADDON, "CANCELED"),
                Arguments.of(Product.ROAMING, "SCHEDULED"),
                Arguments.of(Product.ROAMING, "ACTIVE"),
                Arguments.of(Product.ROAMING, "EXPIRED"),
                Arguments.of(Product.ROAMING, "CANCELED"));
    }

    @ParameterizedTest(name = "{0} {1} 이력 보존")
    @MethodSource("histories")
    @DisplayName("종료된 이력까지 조건 수정·삭제는 차단하고 이름·판매 상태 변경은 허용한다")
    void preservesEveryHistoryStatus(Product product, String historyStatus) throws Exception {
        long id = create(product);
        var history = ProductSubscriptionTestSupport.insertHistory(
                jdbcTemplate, product, id, administrator.getId(), historyStatus);
        String adminItem = "/admin" + product.route + "/" + id;
        Map<String, Object> original = row(product, id);

        mockMvc.perform(put(adminItem).with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON).content(product.updateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(product.errorPrefix + "-004"));
        mockMvc.perform(delete(adminItem).with(user(new CustomUserDetails(administrator))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(product.errorPrefix + "-004"));
        entityManager.flush();
        assertThat(row(product, id)).isEqualTo(original);

        String nameOnly = ProductSubscriptionTestSupport.unchangedUpdateBody(product)
                .replace("\"name\":\"상품\"", "\"name\":\"새 이름\"");
        mockMvc.perform(put(adminItem).with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON).content(nameOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath(product == Product.PLAN
                        ? "$.data.detail.summary.name" : "$.data.detail.name").value("새 이름"));
        entityManager.flush();
        assertThat(conditions(row(product, id))).isEqualTo(conditions(original));

        mockMvc.perform(patch(adminItem + "/status").with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get(product.route + "/" + id)).andExpect(status().isNotFound());
        assertThat(conditions(row(product, id))).isEqualTo(conditions(original));
        assertThat(entityManager.find(Subscription.class, history.subscriptionId())).isNotNull();
        if (product == Product.PLAN) {
            // 가입 관계를 통한 조회는 공개 판매 목록의 ACTIVE 필터에 영향을 받지 않는다.
            assertThat(entityManager.find(Subscription.class, history.subscriptionId()).getPlan().getStatus())
                    .isEqualTo(MasterStatus.INACTIVE);
        }
        mockMvc.perform(patch(adminItem + "/status").with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> protectedFields() {
        return Stream.of(
                change(Product.PLAN, "description", "\"설명\"", "\"수정 설명\""),
                change(Product.PLAN, "networkType", "\"FIVE_G\"", "\"LTE\""),
                change(Product.PLAN, "targetGroup", "\"GENERAL\"", "\"YOUTH\""),
                change(Product.PLAN, "monthlyFee", "10000", "12000"),
                change(Product.PLAN, "dataAmountMb", "1000", "2000"),
                Arguments.of(Product.PLAN, "dataUnlimited", ProductSubscriptionTestSupport.unchangedUpdateBody(Product.PLAN)
                        .replace("\"dataAmountMb\":1000", "\"dataAmountMb\":null")
                        .replace("\"dataUnlimited\":false", "\"dataUnlimited\":true")),
                change(Product.PLAN, "exhaustedSpeedKbps", "null", "128"),
                change(Product.PLAN, "voiceMinutes", "100", "200"),
                Arguments.of(Product.PLAN, "voiceUnlimited", ProductSubscriptionTestSupport.unchangedUpdateBody(Product.PLAN)
                        .replace("\"voiceMinutes\":100", "\"voiceMinutes\":null")
                        .replace("\"voiceUnlimited\":false", "\"voiceUnlimited\":true")),
                change(Product.PLAN, "smsCount", "100", "200"),
                Arguments.of(Product.PLAN, "smsUnlimited", ProductSubscriptionTestSupport.unchangedUpdateBody(Product.PLAN)
                        .replace("\"smsCount\":100", "\"smsCount\":null")
                        .replace("\"smsUnlimited\":false", "\"smsUnlimited\":true")),
                change(Product.PLAN, "tetheringAmountMb", "1000", "500"),
                change(Product.PLAN, "minAge", "null", "19"),
                change(Product.PLAN, "maxAge", "null", "64"),
                change(Product.BUNDLE, "description", "\"설명\"", "\"수정 설명\""),
                change(Product.BUNDLE, "discountAmount", "10000", "12000"),
                change(Product.ADDON, "description", "\"설명\"", "\"수정 설명\""),
                change(Product.ADDON, "monthlyFee", "10000", "12000"),
                change(Product.ROAMING, "description", "\"설명\"", "\"수정 설명\""),
                change(Product.ROAMING, "dailyFee", "10000", "12000"),
                change(Product.ROAMING, "dataAmountMb", "1000", "2000"),
                change(Product.ROAMING, "country", "\"일본\"", "\"미국\""));
    }

    @ParameterizedTest(name = "{0} {1} 변경 차단")
    @MethodSource("protectedFields")
    @DisplayName("각 계약 조건 컬럼의 변경을 개별적으로 차단한다")
    void rejectsEveryProtectedField(Product product, String field, String body) throws Exception {
        long id = create(product);
        ProductSubscriptionTestSupport.insertHistory(jdbcTemplate, product, id, administrator.getId(), "ACTIVE");
        Map<String, Object> original = row(product, id);
        mockMvc.perform(put("/admin" + product.route + "/" + id).with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(product.errorPrefix + "-004"));
        entityManager.flush();
        assertThat(row(product, id)).isEqualTo(original);
    }

    private static Arguments change(Product product, String field, String before, String after) {
        return Arguments.of(product, field, ProductSubscriptionTestSupport.unchangedUpdateBody(product)
                .replace("\"" + field + "\":" + before, "\"" + field + "\":" + after));
    }

    private Map<String, Object> row(Product product, long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM " + product.table
                + " WHERE " + ProductSubscriptionTestSupport.primaryKey(product) + " = ?", id);
    }

    private Map<String, Object> conditions(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>(row);
        Stream.of("name", "status", "updated_at", "updated_by").forEach(result::remove);
        return result;
    }

    private long create(Product product) throws Exception {
        String location = mockMvc.perform(post("/admin" + product.route)
                        .with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON).content(product.createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
