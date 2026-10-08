package com.ubot.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.addon.dto.request.AdminAddonServiceUpdateRequestDto;
import com.ubot.addon.service.AdminAddonService;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.bundle.dto.request.AdminBundleProductUpdateRequestDto;
import com.ubot.bundle.service.AdminBundleProductService;
import com.ubot.common.dto.request.ProductStatusUpdateRequestDto;
import com.ubot.common.enums.MasterStatus;
import com.ubot.plan.dto.request.AdminPlanStatusUpdateRequestDto;
import com.ubot.plan.dto.request.AdminPlanUpdateRequestDto;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import com.ubot.plan.service.AdminPlanService;
import com.ubot.product.ProductCrudIntegrationTest.Product;
import com.ubot.roaming.dto.request.AdminRoamingProductUpdateRequestDto;
import com.ubot.roaming.service.AdminRoamingProductService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("상품 4개 수정·상태 변경·삭제의 PostgreSQL 행 잠금 통합 테스트")
class ProductConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AdminPlanService planService;
    @Autowired
    private AdminBundleProductService bundleService;
    @Autowired
    private AdminAddonService addonService;
    @Autowired
    private AdminRoamingProductService roamingService;

    private User administrator;

    @BeforeEach
    void createAdministrator() {
        LocalDateTime now = LocalDateTime.now();
        administrator = userRepository.saveAndFlush(User.builder()
                .email("product-lock-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only")
                .name("동시 수정 관리자")
                .role(UserRole.ADMIN)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    static Stream<Arguments> mutations() {
        return Stream.of(Product.values()).flatMap(product ->
                Stream.of("update", "status", "delete").map(action -> Arguments.of(product, action)));
    }

    @ParameterizedTest(name = "{0} {1} 행 잠금")
    @MethodSource("mutations")
    @DisplayName("잠금을 획득한 이전 변경이 커밋되기 전에는 서비스 변경 메서드가 반환하지 않는다")
    void waitsForPessimisticRowLock(Product product, String action) throws Exception {
        long id = create(product);
        String pk = product.idField.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attemptingMutation = new CountDownLatch(1);
        CountDownLatch mutationReturned = new CountDownLatch(1);

        try {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> transaction.executeWithoutResult(state -> {
                    jdbcTemplate.queryForObject("SELECT " + pk + " FROM " + product.table
                            + " WHERE " + pk + " = ? FOR UPDATE", Long.class, id);
                    locked.countDown();
                    await(release);
                    jdbcTemplate.update("UPDATE " + product.table + " SET name = ? WHERE " + pk + " = ?",
                            "먼저 수정", id);
                }));
                try {
                    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                    var second = executor.submit(() -> transaction.executeWithoutResult(state -> {
                        attemptingMutation.countDown();
                        mutate(product, action, id);
                        // 서비스 메서드의 반환 시점을 확인해 단순 UPDATE 시점의 DB 잠금과 구분한다.
                        mutationReturned.countDown();
                    }));
                    try {
                        assertThat(attemptingMutation.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(mutationReturned.await(300, TimeUnit.MILLISECONDS)).isFalse();
                    } finally {
                        release.countDown();
                    }
                    first.get(10, TimeUnit.SECONDS);
                    second.get(10, TimeUnit.SECONDS);
                    assertThat(mutationReturned.getCount()).isZero();
                } finally {
                    release.countDown();
                }
            }

            assertThat(jdbcTemplate.queryForObject("SELECT name FROM " + product.table
                    + " WHERE " + pk + " = ?", String.class, id))
                    .isEqualTo(action.equals("update") ? "나중 수정" : "먼저 수정");
            assertThat(jdbcTemplate.queryForObject("SELECT updated_by FROM " + product.table
                    + " WHERE " + pk + " = ?", Long.class, id)).isEqualTo(administrator.getId());
            if (action.equals("delete")) {
                assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM " + product.table
                        + " WHERE " + pk + " = ?", LocalDateTime.class, id)).isNotNull();
            }
        } finally {
            jdbcTemplate.update("DELETE FROM " + product.table + " WHERE " + pk + " = ?", id);
            userRepository.deleteById(administrator.getId());
        }
    }

    private long create(Product product) throws Exception {
        String location = mockMvc.perform(post("/admin" + product.route)
                        .with(user(new CustomUserDetails(administrator)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(product.createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void mutate(Product product, String action, long id) {
        long adminId = administrator.getId();
        if (action.equals("delete")) {
            switch (product) {
                case PLAN -> planService.deletePlan(adminId, id);
                case BUNDLE -> bundleService.deleteBundleProduct(adminId, id);
                case ADDON -> addonService.deleteAddonService(adminId, id);
                case ROAMING -> roamingService.deleteRoamingProduct(adminId, id);
            }
        } else if (action.equals("status")) {
            var request = new ProductStatusUpdateRequestDto(MasterStatus.INACTIVE);
            switch (product) {
                case PLAN -> planService.changeStatus(adminId, id, new AdminPlanStatusUpdateRequestDto(MasterStatus.INACTIVE));
                case BUNDLE -> bundleService.changeStatus(adminId, id, request);
                case ADDON -> addonService.changeStatus(adminId, id, request);
                case ROAMING -> roamingService.changeStatus(adminId, id, request);
            }
        } else {
            switch (product) {
                case PLAN -> planService.updatePlan(adminId, id, new AdminPlanUpdateRequestDto(
                        "나중 수정", null, NetworkType.FIVE_G, PlanTargetGroup.GENERAL,
                        10000, 1000L, false, null, 100, false, 100, false, 1000L, null, null));
                case BUNDLE -> bundleService.updateBundleProduct(adminId, id,
                        new AdminBundleProductUpdateRequestDto("나중 수정", null, 10000));
                case ADDON -> addonService.updateAddonService(adminId, id,
                        new AdminAddonServiceUpdateRequestDto("나중 수정", null, 10000));
                case ROAMING -> roamingService.updateRoamingProduct(adminId, id,
                        new AdminRoamingProductUpdateRequestDto("나중 수정", null, 10000, 1000L, "일본"));
            }
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트의 행 잠금 해제 요청을 기다리지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
