package com.ubot.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.common.ErrorCode;
import com.ubot.store.dto.request.AdminStoreCreateRequestDto;
import com.ubot.store.dto.request.AdminStoreUpdateRequestDto;
import com.ubot.store.dto.response.AdminStoreResponseDto;
import com.ubot.store.exception.StoreException;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Sql("/sql/store-repository-test-setup.sql")
@Transactional
@DisplayName("관리자 매장 Service 통합 테스트")
class AdminStoreServiceIntegrationTest {

    @Autowired
    private AdminStoreService adminStoreService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("매장과 지원 서비스를 등록한다")
    void createsStoreWithServices() {
        AdminStoreResponseDto response = adminStoreService.createStore(createRequest(
                List.of(" APPLE_AS ", "FOREIGN_LANGUAGE_SUPPORT", "APPLE_AS")
        ));
        entityManager.flush();

        assertThat(response.storeId()).isGreaterThan(5L);
        assertThat(response.services())
                .extracting(AdminStoreResponseDto.ServiceResponse::code)
                .containsExactly("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT");
        assertThat(countStoreServices(response.storeId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ST_AsText(location::geometry) FROM stores WHERE store_id = ?",
                String.class,
                response.storeId()
        )).isEqualTo("POINT(127 37.5)");
    }

    @Test
    @DisplayName("활성 상태인 동일 매장은 다시 등록할 수 없다")
    void rejectsDuplicateActiveStore() {
        AdminStoreCreateRequestDto request = new AdminStoreCreateRequestDto(
                "강남역점",
                "서울특별시",
                "강남구",
                "서울특별시 강남구 강남대로 396",
                new BigDecimal("37.4987000"),
                new BigDecimal("127.0286000"),
                "02-1111-1111",
                "10:00-19:00",
                List.of("APPLE_AS")
        );

        assertStoreException(
                () -> adminStoreService.createStore(request),
                ErrorCode.DUPLICATE_STORE
        );
    }

    @Test
    @DisplayName("소프트 삭제된 동일 매장이 존재하면 신규 등록할 수 없다")
    void rejectsCreateWhenSameStoreWasSoftDeleted() {
        long deletedStoreId = 1L;

        adminStoreService.deleteStore(deletedStoreId);
        entityManager.flush();
        entityManager.clear();

        int storeCountBefore = countStores();

        AdminStoreCreateRequestDto request =
                new AdminStoreCreateRequestDto(
                        "강남역점",
                        "서울특별시",
                        "강남구",
                        "서울특별시 강남구 강남대로 396",
                        new BigDecimal("37.4000000"),
                        new BigDecimal("127.1000000"),
                        "031-1234-5678",
                        "10:00-20:00",
                        List.of("IDENTITY_THEFT_REPORT")
                );

        assertStoreException(
                () -> adminStoreService.createStore(request),
                ErrorCode.DELETED_STORE_ALREADY_EXISTS
        );

        assertThat(countStores()).isEqualTo(storeCountBefore);
    }

    @Test
    @DisplayName("요청에 포함된 매장 정보와 지원 서비스만 수정한다")
    void partiallyUpdatesStore() {
        AdminStoreResponseDto response = adminStoreService.updateStore(1L, updateRequest(
                "수정된 강남역점",
                "서울특별시 강남구 수정로 1",
                new BigDecimal("37.5100000"),
                new BigDecimal("127.0100000"),
                List.of("IDENTITY_THEFT_REPORT")
        ));
        entityManager.flush();

        assertThat(response.storeName()).isEqualTo("수정된 강남역점");
        assertThat(response.address()).isEqualTo("서울특별시 강남구 수정로 1");
        assertThat(response.services())
                .extracting(AdminStoreResponseDto.ServiceResponse::code)
                .containsExactly("IDENTITY_THEFT_REPORT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ST_AsText(location::geometry) FROM stores WHERE store_id = 1",
                String.class
        )).isEqualTo("POINT(127.01 37.51)");
    }

    @Test
    @DisplayName("serviceCodes를 전달하지 않으면 기존 서비스 관계를 유지한다")
    void keepsServicesWhenServiceCodesAreNull() {
        adminStoreService.updateStore(1L, updateRequest(
                "매장명만 수정",
                null,
                null,
                null,
                null
        ));
        entityManager.flush();

        assertThat(countStoreServices(1L)).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 serviceCodes를 전달하면 모든 서비스 관계를 제거한다")
    void clearsServicesWhenServiceCodesAreEmpty() {
        AdminStoreResponseDto response = adminStoreService.updateStore(1L, updateRequest(
                null,
                null,
                null,
                null,
                List.of()
        ));
        entityManager.flush();

        assertThat(response.services()).isEmpty();
        assertThat(countStoreServices(1L)).isZero();
    }

    @Test
    @DisplayName("매장을 소프트 삭제한다")
    void softDeletesStore() {
        adminStoreService.deleteStore(1L);
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM stores WHERE store_id = 1",
                Boolean.class
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM stores WHERE store_id = 1",
                Boolean.class
        )).isTrue();
    }

    @Test
    @DisplayName("소프트 삭제된 매장을 다시 활성화한다")
    void activatesSoftDeletedStore() {
        long storeId = 1L;

        int storeCountBefore = countStores();
        int serviceCountBefore = countStoreServices(storeId);

        adminStoreService.deleteStore(storeId);
        entityManager.flush();
        entityManager.clear();

        Boolean deletedActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM stores WHERE store_id = ?",
                Boolean.class,
                storeId
        );

        LocalDateTime deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM stores WHERE store_id = ?",
                LocalDateTime.class,
                storeId
        );

        assertThat(deletedActive).isFalse();
        assertThat(deletedAt).isNotNull();

        AdminStoreResponseDto response = adminStoreService.activateStore(storeId);

        entityManager.flush();
        entityManager.clear();

        assertThat(response.storeId()).isEqualTo(storeId);
        assertThat(response.active()).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM stores WHERE store_id = ?",
                Boolean.class,
                storeId
        )).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NULL FROM stores WHERE store_id = ?",
                Boolean.class,
                storeId
        )).isTrue();

        assertThat(countStores()).isEqualTo(storeCountBefore);

        assertThat(countStoreServices(storeId)).isEqualTo(serviceCountBefore);
    }

    @Test
    @DisplayName("활성 상태인 매장은 복구할 수 없다")
    void rejectsActiveStoreOnActivate() {
        assertStoreException(
                () -> adminStoreService.activateStore(1L),
                ErrorCode.STORE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("삭제 이력이 없는 비활성 매장은 복구할 수 없다")
    void rejectsInactiveStoreOnActivate() {
        assertStoreException(
                () -> adminStoreService.activateStore(1L),
                ErrorCode.STORE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("존재하지 않는 매장은 복구할 수 없다")
    void rejectsMissingStoreOnActivate() {
        assertStoreException(
                () -> adminStoreService.activateStore(1L),
                ErrorCode.STORE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("다른 활성 매장과 동일한 매장명과 주소로 수정할 수 없다")
    void rejectsDuplicateStoreOnUpdate() {
        AdminStoreCreateRequestDto createRequest = new AdminStoreCreateRequestDto(
                "중복 테스트 매장",
                "서울특별시",
                "강남구",
                "서울특별시 강남구 중복로 1",
                new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000"),
                "02-1234-5678",
                "09:00-18:00",
                List.of("APPLE_AS")
        );

        AdminStoreResponseDto createdStore = adminStoreService.createStore(createRequest);

        entityManager.flush();

        assertStoreException(
                () -> adminStoreService.updateStore(
                        1L,
                        updateRequest(
                                createdStore.storeName(),
                                createdStore.address(),
                                null,
                                null,
                                null
                        )
                ),
                ErrorCode.DUPLICATE_STORE
        );
    }

    @Test
    @DisplayName("자기 자신의 매장명과 주소로 수정하는 것은 중복으로 판단하지 않는다")
    void allowsSameStoreNameAndAddressOnUpdate() {
        String storeName = jdbcTemplate.queryForObject(
                "SELECT store_name FROM stores WHERE store_id = 1",
                String.class
        );

        String address = jdbcTemplate.queryForObject(
                "SELECT address FROM stores WHERE store_id = 1",
                String.class
        );

        AdminStoreResponseDto response = adminStoreService.updateStore(
                1L,
                updateRequest(
                        storeName,
                        address,
                        null,
                        null,
                        null
                )
        );

        assertThat(response.storeId()).isEqualTo(1L);
        assertThat(response.storeName()).isEqualTo(storeName);
        assertThat(response.address()).isEqualTo(address);
    }

    @Test
    @DisplayName("존재하지 않는 매장은 수정하거나 삭제할 수 없다")
    void rejectsMissingStore() {
        assertStoreException(
                () -> adminStoreService.updateStore(
                        999L,
                        updateRequest("없는 매장", null, null, null, null)
                ),
                ErrorCode.STORE_NOT_FOUND
        );

        assertStoreException(
                () -> adminStoreService.deleteStore(999L),
                ErrorCode.STORE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("비활성화되거나 삭제된 매장은 수정하거나 삭제할 수 없다")
    void rejectsInactiveOrDeletedStore() {
        assertStoreException(
                () -> adminStoreService.updateStore(
                        4L,
                        updateRequest("비활성 매장", null, null, null, null)
                ),
                ErrorCode.STORE_NOT_FOUND
        );
        assertStoreException(
                () -> adminStoreService.deleteStore(4L),
                ErrorCode.STORE_NOT_FOUND
        );
        assertStoreException(
                () -> adminStoreService.updateStore(
                        5L,
                        updateRequest("삭제 매장", null, null, null, null)
                ),
                ErrorCode.STORE_NOT_FOUND
        );
        assertStoreException(
                () -> adminStoreService.deleteStore(5L),
                ErrorCode.STORE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("존재하지 않는 서비스 코드가 포함되면 요청 전체를 실패시킨다")
    void rejectsUnknownServiceCode() {
        assertStoreException(
                () -> adminStoreService.createStore(
                        createRequest(List.of("APPLE_AS", "UNKNOWN_SERVICE"))
                ),
                ErrorCode.SERVICE_TYPE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("비활성 서비스 코드가 포함되면 요청 전체를 실패시킨다")
    void rejectsInactiveServiceCode() {
        assertStoreException(
                () -> adminStoreService.updateStore(
                        1L,
                        updateRequest(
                                null,
                                null,
                                null,
                                null,
                                List.of("INACTIVE_SERVICE")
                        )
                ),
                ErrorCode.SERVICE_TYPE_NOT_FOUND
        );
    }

    @Test
    @DisplayName("PATCH에서 위도나 경도 중 하나만 전달하면 실패한다")
    void rejectsIncompleteCoordinates() {
        assertStoreException(
                () -> adminStoreService.updateStore(
                        1L,
                        updateRequest(
                                null,
                                null,
                                new BigDecimal("37.5"),
                                null,
                                null
                        )
                ),
                ErrorCode.INVALID_STORE_COORDINATES
        );
    }


    @Test
    @DisplayName("지원 서비스만 수정해도 updatedAt이 갱신된다")
    void updatesUpdatedAtWhenOnlyServicesChange() {
        jdbcTemplate.update(
                "UPDATE stores SET updated_at = ? WHERE store_id = ?",
                LocalDateTime.of(2025, 1, 1, 0, 0),
                1L
        );

        LocalDateTime before = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM stores WHERE store_id = 1",
                LocalDateTime.class
        );

        adminStoreService.updateStore(
                1L,
                updateRequest(null, null, null, null, List.of("IDENTITY_THEFT_REPORT"))
        );

        entityManager.flush();

        LocalDateTime after = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM stores WHERE store_id = 1",
                LocalDateTime.class
        );

        assertThat(after).isAfter(before);
    }

    private AdminStoreCreateRequestDto createRequest(List<String> serviceCodes) {
        return new AdminStoreCreateRequestDto(
                "신규 매장",
                "서울특별시",
                "강남구",
                "서울특별시 강남구 테스트로 1",
                new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000"),
                "02-0000-0000",
                "09:00-18:00",
                serviceCodes
        );
    }

    private AdminStoreUpdateRequestDto updateRequest(
            String storeName,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            List<String> serviceCodes
    ) {
        return new AdminStoreUpdateRequestDto(
                storeName,
                null,
                null,
                address,
                latitude,
                longitude,
                null,
                null,
                serviceCodes
        );
    }

    private int countStoreServices(long storeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM store_services WHERE store_id = ?",
                Integer.class,
                storeId
        );
    }

    private int countStores() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stores", Integer.class);
    }

    private void assertStoreException(
            ThrowingCallable callable,
            ErrorCode expectedErrorCode
    ) {
        assertThatThrownBy(callable)
                .isInstanceOf(StoreException.class)
                .extracting(exception -> ((StoreException) exception).getErrorCode())
                .isEqualTo(expectedErrorCode);
    }

}
