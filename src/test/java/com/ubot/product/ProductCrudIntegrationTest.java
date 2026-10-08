package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("상품 CRUD·공개 조회·관리자 권한 통합 테스트")
class ProductCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User administrator;

    @BeforeEach
    void createAdministrator() {
        LocalDateTime now = LocalDateTime.now();
        administrator = userRepository.saveAndFlush(User.builder()
                .email("product-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only")
                .name("상품 관리자")
                .role(UserRole.ADMIN)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("4개 상품을 생성·공개 조회·전체 수정·상태 변경·소프트 삭제한다")
    void completesCrudLifecycle(Product product) throws Exception {
        long id = create(product, product.createBody);
        String item = product.route + "/" + id;
        String adminItem = "/admin" + item;
        String detailPath = product == Product.PLAN ? "$.data.summary" : "$.data";

        mockMvc.perform(get(item))
                .andExpect(status().isOk())
                .andExpect(jsonPath(detailPath + ".name").value("상품"));
        mockMvc.perform(get(product.route).param("keyword", "상품"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get(adminItem).with(administrator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata.createdBy").value(administrator.getId()))
                .andExpect(jsonPath("$.data.metadata.updatedBy").value(administrator.getId()))
                .andExpect(jsonPath("$.data.metadata.createdAt").isNotEmpty());

        mockMvc.perform(put(adminItem).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content(product.updateBody))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get(item))
                .andExpect(status().isOk())
                .andExpect(jsonPath(detailPath + ".name").value("수정 상품"))
                .andExpect(jsonPath("$.data.description").doesNotExist());
        if (product == Product.PLAN) {
            mockMvc.perform(get(item))
                    .andExpect(jsonPath("$.data.summary.dataUnlimited").value(true))
                    .andExpect(jsonPath("$.data.summary.dataAmountMb").doesNotExist())
                    .andExpect(jsonPath("$.data.summary.voiceMinutes").doesNotExist())
                    .andExpect(jsonPath("$.data.summary.smsCount").doesNotExist());
        }

        mockMvc.perform(patch(adminItem + "/status").with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(item)).andExpect(status().isNotFound());
        mockMvc.perform(get(product.route).param("keyword", "수정 상품"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/admin" + product.route).with(administrator()).param("status", "INACTIVE"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(patch(adminItem + "/status").with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(item)).andExpect(status().isOk());

        mockMvc.perform(delete(adminItem).with(administrator())).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get(item)).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin" + product.route).with(administrator()).param("deleted", "true"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get(adminItem).with(administrator()))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.metadata.deletedAt").isNotEmpty());
        mockMvc.perform(put(adminItem).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content(product.updateBody))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(adminItem + "/status").with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(adminItem).with(administrator())).andExpect(status().isNotFound());
        mockMvc.perform(post("/admin" + product.route).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content(product.createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(product.errorPrefix + "-002"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + product.table
                + " WHERE " + snakeCase(product.idField) + " = ? AND deleted_at IS NOT NULL",
                Integer.class, id)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("비회원도 목록·상세 조회에 접근한다")
    void allowsAnonymousRead(Product product) throws Exception {
        mockMvc.perform(get(product.route)).andExpect(status().isOk());
        mockMvc.perform(get(product.route + "/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(product.errorPrefix + "-001"));
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("비회원의 생성·수정·상태 변경·삭제 및 관리자 조회는 401이다")
    void rejectsAnonymousAdminAccess(Product product) throws Exception {
        mockMvc.perform(post("/admin" + product.route).contentType(MediaType.APPLICATION_JSON)
                .content(product.createBody)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin" + product.route + "/1").contentType(MediaType.APPLICATION_JSON)
                .content(product.updateBody)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/admin" + product.route + "/1/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin" + product.route + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin" + product.route)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("일반 회원의 생성·수정·상태 변경·삭제 및 관리자 조회는 403이다")
    void rejectsUserAdminAccess(Product product) throws Exception {
        RequestPostProcessor member = user("member@test.com").roles("USER");
        mockMvc.perform(post("/admin" + product.route).with(member).contentType(MediaType.APPLICATION_JSON)
                .content(product.createBody)).andExpect(status().isForbidden());
        mockMvc.perform(put("/admin" + product.route + "/1").with(member).contentType(MediaType.APPLICATION_JSON)
                .content(product.updateBody)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/admin" + product.route + "/1/status").with(member).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin" + product.route + "/1").with(member)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin" + product.route).with(member)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("상품 코드는 유일하고 등록 실패 시 새 행을 만들지 않는다")
    void rejectsDuplicateCode(Product product) throws Exception {
        create(product, product.createBody);
        mockMvc.perform(post("/admin" + product.route).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content(product.createBody))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + product.table,
                Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("빈 이름과 유효하지 않은 페이지는 공통 400 응답이다")
    void rejectsInvalidInput(Product product) throws Exception {
        mockMvc.perform(post("/admin" + product.route).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(product.createBody.replace("  상품  ", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-001"));
        mockMvc.perform(get(product.route).param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get(product.route).param("size", "101")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(Product.class)
    @DisplayName("검색어의 SQL 와일드카드를 일반 문자로 검색한다")
    void escapesSearchWildcard(Product product) throws Exception {
        create(product, product.createBody.replace("  상품  ", "상품%_"));
        create(product, product.createBody.replace("TEST_", "OTHER_").replace("  상품  ", "다른 상품"));
        mockMvc.perform(get(product.route).param("keyword", "%_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"dataUnlimited\":false=>\"dataUnlimited\":true",
            "\"voiceUnlimited\":false=>\"voiceUnlimited\":true",
            "\"smsUnlimited\":false=>\"smsUnlimited\":true",
            "\"minAge\":null,\"maxAge\":null=>\"minAge\":20,\"maxAge\":10"
    })
    @DisplayName("무제한·제공량 및 나이 조합이 잘못되면 저장하지 않는다")
    void rejectsInvalidPlanCombination(String replacement) throws Exception {
        String[] parts = replacement.split("=>");
        mockMvc.perform(post("/admin/plans").with(administrator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Product.PLAN.createBody.replace(parts[0], parts[1])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN-003"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM plans", Integer.class)).isZero();
    }

    @Test
    @DisplayName("요금제 수정 조합 검증 실패 시 기존 값은 유지된다")
    void preservesPlanWhenUpdateValidationFails() throws Exception {
        long id = create(Product.PLAN, Product.PLAN.createBody);
        mockMvc.perform(put("/admin/plans/" + id).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Product.PLAN.updateBody.replace("\"dataAmountMb\":null", "\"dataAmountMb\":1000")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/plans/" + id))
                .andExpect(jsonPath("$.data.summary.name").value("상품"))
                .andExpect(jsonPath("$.data.summary.dataUnlimited").value(false));
    }

    @Test
    @DisplayName("로밍은 국가 하나로 정확히 필터링한다")
    void filtersRoamingCountry() throws Exception {
        create(Product.ROAMING, Product.ROAMING.createBody);
        mockMvc.perform(get("/roaming-products").param("country", " 일본 "))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/roaming-products").param("country", "미국"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private long create(Product product, String body) throws Exception {
        String location = mockMvc.perform(post("/admin" + product.route).with(administrator())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("/admin" + product.route + "/");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private RequestPostProcessor administrator() {
        return user(new CustomUserDetails(administrator));
    }

    private String snakeCase(String value) {
        return value.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
    }

    enum Product {
        PLAN("/plans", "plans", "planId", "PLAN", "{\"planCode\":\"TEST_PLAN\",\"name\":\"  상품  \",\"description\":\"설명\",\"networkType\":\"FIVE_G\",\"targetGroup\":\"GENERAL\",\"monthlyFee\":10000,\"dataAmountMb\":1000,\"dataUnlimited\":false,\"exhaustedSpeedKbps\":null,\"voiceMinutes\":100,\"voiceUnlimited\":false,\"smsCount\":100,\"smsUnlimited\":false,\"tetheringAmountMb\":1000,\"minAge\":null,\"maxAge\":null}", "{\"name\":\"수정 상품\",\"description\":null,\"networkType\":\"FIVE_G\",\"targetGroup\":\"GENERAL\",\"monthlyFee\":10000,\"dataAmountMb\":null,\"dataUnlimited\":true,\"exhaustedSpeedKbps\":null,\"voiceMinutes\":null,\"voiceUnlimited\":true,\"smsCount\":null,\"smsUnlimited\":true,\"tetheringAmountMb\":1000,\"minAge\":null,\"maxAge\":null}"),
        BUNDLE("/bundle-products", "bundle_products", "bundleProductId", "BUNDLE", "{\"code\":\"TEST_BUNDLE\",\"name\":\"  상품  \",\"description\":\"설명\",\"discountAmount\":10000}", "{\"name\":\"수정 상품\",\"description\":null,\"discountAmount\":10000}"),
        ADDON("/addon-services", "addon_services", "addonServiceId", "ADDON", "{\"code\":\"TEST_ADDON\",\"name\":\"  상품  \",\"description\":\"설명\",\"monthlyFee\":10000}", "{\"name\":\"수정 상품\",\"description\":null,\"monthlyFee\":10000}"),
        ROAMING("/roaming-products", "roaming_products", "roamingProductId", "ROAMING", "{\"code\":\"TEST_ROAMING\",\"name\":\"  상품  \",\"description\":\"설명\",\"dailyFee\":10000,\"dataAmountMb\":1000,\"country\":\"일본\"}", "{\"name\":\"수정 상품\",\"description\":null,\"dailyFee\":10000,\"dataAmountMb\":1000,\"country\":\"일본\"}");

        final String route;
        final String table;
        final String idField;
        final String errorPrefix;
        final String createBody;
        final String updateBody;

        Product(String route, String table, String idField, String errorPrefix, String createBody, String updateBody) {
            this.route = route;
            this.table = table;
            this.idField = idField;
            this.errorPrefix = errorPrefix;
            this.createBody = createBody;
            this.updateBody = updateBody;
        }
    }
}
