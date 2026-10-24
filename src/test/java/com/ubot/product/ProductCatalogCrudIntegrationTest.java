package com.ubot.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.UUID;

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
@DisplayName("인터넷/IPTV 상품·요금제 포함 부가서비스 CRUD와 목록 필터 통합 테스트")
class ProductCatalogCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private User administrator;
    private User member;

    @BeforeEach
    void createUsers() {
        administrator = createUser(UserRole.ADMIN);
        member = createUser(UserRole.USER);
    }

    @Test
    @DisplayName("인터넷/IPTV 상품을 등록·공개 조회·수정·판매 중지·삭제한다")
    void completesHomeProductLifecycle() throws Exception {
        long id = created(send(post("/admin/home-products"), administrator, """
                {"code":"TEST_INTERNET","name":"  테스트 인터넷  ","description":"설명",
                 "productType":"INTERNET","speedMbps":2500,"monthlyFee":44000}
                """));
        mockMvc.perform(get("/home-products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("테스트 인터넷"))
                .andExpect(jsonPath("$.data.speedMbps").value(2500));
        mockMvc.perform(get("/home-products").param("keyword", "테스트 인터넷").param("productType", "INTERNET"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/home-products").param("keyword", "테스트 인터넷").param("productType", "IPTV"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/admin/home-products/" + id).with(as(administrator)))
                .andExpect(jsonPath("$.data.metadata.createdBy").value(administrator.getId()));

        send(put("/admin/home-products/" + id), administrator, """
                {"name":"수정 인터넷","description":null,"speedMbps":5000,"monthlyFee":55000}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.speedMbps").value(5000))
                .andExpect(jsonPath("$.data.detail.description").doesNotExist());

        send(patch("/admin/home-products/" + id + "/status"), administrator, "{\"status\":\"INACTIVE\"}")
                .andExpect(status().isOk());
        mockMvc.perform(get("/home-products/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOME-001"));
        send(patch("/admin/home-products/" + id + "/status"), administrator, "{\"status\":\"ACTIVE\"}")
                .andExpect(status().isOk());

        mockMvc.perform(delete("/admin/home-products/" + id).with(as(administrator)))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/home-products/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/home-products/" + id).with(as(administrator)))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.metadata.deletedAt").isNotEmpty());
        send(post("/admin/home-products"), administrator, """
                {"code":"TEST_INTERNET","name":"코드 재사용","productType":"INTERNET","speedMbps":100,"monthlyFee":1}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOME-002"));
    }

    @Test
    @DisplayName("인터넷은 속도가 있어야 하고 IPTV는 속도가 없어야 한다")
    void validatesSpeedByProductType() throws Exception {
        send(post("/admin/home-products"), administrator, """
                {"code":"TEST_NO_SPEED","name":"속도 없는 인터넷","productType":"INTERNET","monthlyFee":1000}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOME-003"));
        send(post("/admin/home-products"), administrator, """
                {"code":"TEST_IPTV_SPEED","name":"속도 있는 IPTV","productType":"IPTV","speedMbps":100,"monthlyFee":1000}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOME-003"));
        long iptvId = id("home_products", "home_product_id", "code", "UBOT_HOME_IPTV_BASIC");
        send(put("/admin/home-products/" + iptvId), administrator, """
                {"name":"IPTV","speedMbps":100,"monthlyFee":16500}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HOME-003"));
    }

    @Test
    @DisplayName("가입 이력이 있거나 홈 결합이 연결한 인터넷 상품은 조건 변경·삭제를 막는다")
    void protectsHomeProductInUse() throws Exception {
        long iptvId = id("home_products", "home_product_id", "code", "UBOT_HOME_IPTV_PREMIUM");
        jdbc.update("""
                INSERT INTO home_subscriptions (user_id, home_product_id, product_type, joined_at,
                    product_name_snapshot, monthly_fee_snapshot)
                VALUES (?, ?, 'IPTV', CURRENT_TIMESTAMP, 'IPTV 프리미엄', 22000)
                """, member.getId(), iptvId);
        send(put("/admin/home-products/" + iptvId), administrator, """
                {"name":"Ubot IPTV 프리미엄","description":"새 설명","monthlyFee":30000}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOME-004"));
        mockMvc.perform(delete("/admin/home-products/" + iptvId).with(as(administrator)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOME-004"));

        long internetId = id("home_products", "home_product_id", "code", "UBOT_HOME_INTERNET_100");
        mockMvc.perform(delete("/admin/home-products/" + internetId).with(as(administrator)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOME-004"));
    }

    @Test
    @DisplayName("관리자 API는 ADMIN만 쓰고, 공개 조회는 로그인 없이 된다")
    void restrictsAdminApi() throws Exception {
        mockMvc.perform(get("/home-products")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/home-products").with(as(member))).andExpect(status().isForbidden());
        send(post("/admin/home-products"), member, "{}").andExpect(status().isForbidden());
        long planId = id("plans", "plan_id", "plan_code", "UBOT_LIFE_01");
        send(put("/admin/plans/" + planId + "/included-addons"), member, "{\"addonServiceIds\":[]}")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("요금제 포함 부가서비스를 조회하고 관리자가 목록을 통째로 바꾼다")
    void managesPlanIncludedAddons() throws Exception {
        long lifeMusic = id("plans", "plan_id", "plan_code", "UBOT_LIFE_01");
        mockMvc.perform(get("/plans/" + lifeMusic + "/included-addons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("UBOT_ADDON_MUSIC"))
                .andExpect(jsonPath("$.data[0].category").value("CONTENT"));

        long premium = id("plans", "plan_id", "plan_code", "UBOT_BALANCE_05");
        long video = id("addon_services", "addon_service_id", "code", "UBOT_ADDON_VIDEO");
        long phishing = id("addon_services", "addon_service_id", "code", "UBOT_ADDON_PHISHING");
        send(put("/admin/plans/" + premium + "/included-addons"), administrator,
                "{\"addonServiceIds\":[%d,%d,%d]}".formatted(video, phishing, video))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/plans/" + premium + "/included-addons"))
                .andExpect(jsonPath("$.data.length()").value(2));

        send(put("/admin/plans/" + premium + "/included-addons"), administrator, "{\"addonServiceIds\":[999999999]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN-003"));
        send(put("/admin/plans/" + premium + "/included-addons"), administrator, "{\"addonServiceIds\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        jdbc.update("UPDATE plans SET status = 'INACTIVE' WHERE plan_id = ?", lifeMusic);
        entityManager.clear();
        mockMvc.perform(get("/plans/" + lifeMusic + "/included-addons"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("목록 조회를 상품 종류·결합 종류·부가서비스 분류로 거른다")
    void filtersProductLists() throws Exception {
        mockMvc.perform(get("/home-products").param("productType", "INTERNET"))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mockMvc.perform(get("/bundle-products").param("bundleType", "HOME"))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mockMvc.perform(get("/addon-services").param("category", "DATA_CHARGE"))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mockMvc.perform(get("/addon-services").param("category", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, User actor, String body) throws Exception {
        return mockMvc.perform(request.with(as(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private RequestPostProcessor as(User actor) {
        return user(new CustomUserDetails(actor));
    }

    private long created(ResultActions result) throws Exception {
        String response = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"homeProductId\":(\\d+).*", "$1"));
    }

    private long id(String table, String idColumn, String codeColumn, String code) {
        return jdbc.queryForObject("SELECT " + idColumn + " FROM " + table + " WHERE " + codeColumn + " = ?",
                Long.class, code);
    }

    private User createUser(UserRole role) {
        LocalDateTime now = LocalDateTime.now();
        return userRepository.saveAndFlush(User.builder()
                .email("catalog-crud-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only").name("상품 테스트").role(role)
                .createdAt(now).updatedAt(now).build());
    }
}
