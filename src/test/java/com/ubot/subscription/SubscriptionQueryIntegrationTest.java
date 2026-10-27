package com.ubot.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.maxmind.geoip2.DatabaseReader;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.billing.service.BillingService;
import com.ubot.contract.service.ContractService;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.service.UsageService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@DisplayName("본인 회선 통신정보 조회 API 통합 테스트")
class SubscriptionQueryIntegrationTest {
	private static final String BASE = "/subscriptions/me";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JwtUtil jwtUtil;
	@Autowired
	private SubscriptionService subscriptionService;
	@Autowired
	private UsageService usageService;
	@Autowired
	private BillingService billingService;
	@Autowired
	private ContractService contractService;
	@MockitoBean
	private DatabaseReader geoIpDatabaseReader;

	private User owner;
	private User other;
	private long subscriptionId;
	private long terminatedId;
	private String token;

	@BeforeEach
	void createFixtures() {
		owner = createUser(UserRole.USER);
		other = createUser(UserRole.USER);
		token = jwtUtil.createAccessToken(owner);
		long planId = jdbc.queryForObject("""
				INSERT INTO plans (plan_code, name, description, network_type, target_group, monthly_fee,
				    data_amount_mb, voice_minutes, sms_count, tethering_amount_mb, status)
				VALUES (?, '가입 요금제', '기존 가입자 요금제', 'FIVE_G', 'GENERAL', 40000,
				    999999, 999, 999, 1000, 'INACTIVE') RETURNING plan_id
				""", Long.class, UUID.randomUUID().toString());
		subscriptionId = createSubscription(owner.getId(), planId, "ACTIVE", "2026-01-01");
		createSubscription(owner.getId(), planId, "SUSPENDED", "2025-01-01");
		terminatedId = createSubscription(owner.getId(), planId, "TERMINATED", "2024-01-01");
		createSubscription(other.getId(), planId, "ACTIVE", "2026-01-01");
		jdbc.update("""
				UPDATE subscriptions SET sim_type = 'ESIM', sim_identifier = '8901234567890123456',
				    sim_status = 'ACTIVE', sim_activated_at = activated_at WHERE subscription_id = ?
				""", subscriptionId);
		seedMonthlyRecords();
		seedContracts();
		seedJoinedProducts();
	}

	@Test
	@DisplayName("JWT의 사용자 회선만 조회하고 정지·해지 이력과 마스킹된 SIM 정보를 제공한다")
	void getsOnlyMySubscriptions() throws Exception {
		String response = mockMvc.perform(get(BASE).header("Authorization", tokenHeader())
				.param("userId", other.getId().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3))
				.andExpect(jsonPath("$.data[0].subscriptionId").value(subscriptionId))
				.andExpect(jsonPath("$.data[0].simType").value("ESIM"))
				.andExpect(jsonPath("$.data[0].maskedSimIdentifier").value("***************3456"))
				.andExpect(jsonPath("$.data[1].status").value("SUSPENDED"))
				.andExpect(jsonPath("$.data[2].status").value("TERMINATED"))
				.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain("8901234567890123456", "simIdentifier", "password", "userId", owner.getEmail());
		mockMvc.perform(get(BASE + "/" + terminatedId).header("Authorization", tokenHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("TERMINATED"))
				.andExpect(jsonPath("$.data.terminatedAt").isNotEmpty());
	}

	@Test
	@DisplayName("판매 중지된 요금제도 가입자가 조회하며 상품 공개 조회는 계속 차단된다")
	void getsInactiveSubscribedPlan() throws Exception {
		mockMvc.perform(get(route("/plan")).header("Authorization", tokenHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.summary.name").value("가입 요금제"))
				.andExpect(jsonPath("$.data.description").value("기존 가입자 요금제"));
		long planId = jdbc.queryForObject("SELECT plan_id FROM subscriptions WHERE subscription_id = ?", Long.class, subscriptionId);
		mockMvc.perform(get("/plans/" + planId)).andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("사용량은 해당 월 스냅샷으로 잔여량을 계산하고 조회 월과 집계 기준을 반환한다")
	void getsMonthlyUsageFromSnapshot() throws Exception {
		mockMvc.perform(get(route("/usage-records")).header("Authorization", tokenHeader()).param("month", "2026-10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.content[0].billingMonth").value("2026-10"))
				.andExpect(jsonPath("$.data.content[0].dataUsedMb").value(1000))
				.andExpect(jsonPath("$.data.content[0].allowance.dataAmountMb").value(5000))
				.andExpect(jsonPath("$.data.content[0].remainingDataMb").value(4000))
				.andExpect(jsonPath("$.data.content[0].remainingVoiceMinutes").value(20))
				.andExpect(jsonPath("$.data.content[0].remainingSmsCount").value(40))
				.andExpect(jsonPath("$.data.content[0].aggregatedThroughDate").value("2026-10-10"))
				.andExpect(jsonPath("$.data.content[0].finalized").value(false))
				.andExpect(jsonPath("$.data.content[0].allowance.internalNote").doesNotExist());
		mockMvc.perform(get(route("/usage-records")).header("Authorization", tokenHeader()).param("month", "2026-09"))
				.andExpect(jsonPath("$.data.content[0].remainingDataMb").value(0))
				.andExpect(jsonPath("$.data.content[0].allowance.voiceUnlimited").value(true))
				.andExpect(jsonPath("$.data.content[0].remainingVoiceMinutes").doesNotExist())
				.andExpect(jsonPath("$.data.content[0].finalized").value(true))
				.andExpect(jsonPath("$.data.content[0].fullMonthActive").value(false));
		mockMvc.perform(get(route("/usage-records")).header("Authorization", tokenHeader()).param("month", "2026-08"))
				.andExpect(jsonPath("$.data.content[0].dataUsedMb").value(1234))
				.andExpect(jsonPath("$.data.content[0].allowance").doesNotExist())
				.andExpect(jsonPath("$.data.content[0].remainingDataMb").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/usage-records", "/billing-records"})
	@DisplayName("월별 이력은 최신순으로 페이지 조회하고 없는 월은 빈 목록으로 반환한다")
	void paginatesMonthlyHistory(String suffix) throws Exception {
		mockMvc.perform(get(route(suffix)).header("Authorization", tokenHeader()).param("page", "0").param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(1))
				.andExpect(jsonPath("$.data.content[0].billingMonth").value("2026-10"))
				.andExpect(jsonPath("$.data.first").value(true))
				.andExpect(jsonPath("$.data.last").value(false));
		mockMvc.perform(get(route(suffix)).header("Authorization", tokenHeader()).param("page", "1").param("size", "1"))
				.andExpect(jsonPath("$.data.content[0].billingMonth").value("2026-09"));
		mockMvc.perform(get(route(suffix)).header("Authorization", tokenHeader()).param("month", "2020-01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content").isEmpty())
				.andExpect(jsonPath("$.data.totalElements").value(0));
	}

	@Test
	@DisplayName("저장된 청구·납부 내역을 반환하며 할인이 요금보다 큰 청구도 다시 계산하지 않는다")
	void getsStoredBillingAndPayment() throws Exception {
		mockMvc.perform(get(route("/billing-records")).header("Authorization", tokenHeader()).param("month", "2026-10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].baseFee").value(40000))
				.andExpect(jsonPath("$.data.content[0].addonFee").value(1000))
				.andExpect(jsonPath("$.data.content[0].roamingFee").value(2000))
				.andExpect(jsonPath("$.data.content[0].extraFee").value(3000))
				.andExpect(jsonPath("$.data.content[0].discountAmount").value(10000))
				.andExpect(jsonPath("$.data.content[0].totalAmount").value(36000))
				.andExpect(jsonPath("$.data.content[0].paymentStatus").value("PAID"))
				.andExpect(jsonPath("$.data.content[0].paidAmount").value(36000))
				.andExpect(jsonPath("$.data.content[0].paymentMethod").value("AUTO_TRANSFER"))
				.andExpect(jsonPath("$.data.content[0].paidAt").isNotEmpty());
		mockMvc.perform(get(route("/billing-records")).header("Authorization", tokenHeader()).param("month", "2026-09"))
				.andExpect(jsonPath("$.data.content[0].discountAmount").value(50000))
				.andExpect(jsonPath("$.data.content[0].totalAmount").value(0))
				.andExpect(jsonPath("$.data.content[0].paymentMethod").doesNotExist());
	}

	@Test
	@DisplayName("현재 약정·할부와 과거 약정 이력을 각각 조회한다")
	void getsCurrentContractAndHistory() throws Exception {
		mockMvc.perform(get(route("/contract")).header("Authorization", tokenHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.contractType").value("DEVICE_SUBSIDY"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.installment.deviceName").value("테스트 단말"))
				.andExpect(jsonPath("$.data.installment.installmentMonths").value(24))
				.andExpect(jsonPath("$.data.installment.paidInstallmentMonths").value(9))
				.andExpect(jsonPath("$.data.installment.remainingInstallmentAmount").value(750000));
		mockMvc.perform(get(route("/contracts")).header("Authorization", tokenHeader()))
				.andExpect(jsonPath("$.data.length()").value(3))
				.andExpect(jsonPath("$.data[1].status").value("EXPIRED"))
				.andExpect(jsonPath("$.data[1].installment").doesNotExist())
				.andExpect(jsonPath("$.data[2].status").value("CANCELED"));
	}

	@Test
	@DisplayName("가입 상품의 현재·종료 이력은 판매 상태와 무관하게 당시 이름·금액으로 조회한다")
	void getsJoinedProductsFromSnapshots() throws Exception {
		mockMvc.perform(get(route("/bundles")).header("Authorization", tokenHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].name").value("가입 당시 결합"))
				.andExpect(jsonPath("$.data[0].appliedDiscountAmount").value(7000))
				.andExpect(jsonPath("$.data[1].status").value("ENDED"));
		mockMvc.perform(get(route("/addons")).header("Authorization", tokenHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].name").value("가입 당시 부가서비스"))
				.andExpect(jsonPath("$.data[0].monthlyFee").value(1000))
				.andExpect(jsonPath("$.data[1].status").value("CANCELED"));
		mockMvc.perform(get(route("/roamings")).header("Authorization", tokenHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(4))
				.andExpect(jsonPath("$.data[0].name").value("가입 당시 로밍"))
				.andExpect(jsonPath("$.data[0].country").value("일본"))
				.andExpect(jsonPath("$.data[0].dailyFee").value(5000))
				.andExpect(jsonPath("$.data[0].dataAmountMb").value(5000))
				.andExpect(jsonPath("$.data[0].status").value("SCHEDULED"))
				.andExpect(jsonPath("$.data[1].status").value("ACTIVE"))
				.andExpect(jsonPath("$.data[2].status").value("EXPIRED"))
				.andExpect(jsonPath("$.data[3].status").value("CANCELED"));
	}

	@Test
	@DisplayName("가입 정보가 없으면 현재 약정은 null, 이력은 빈 목록으로 반환한다")
	void returnsAbsenceWithoutFabricatingRecords() throws Exception {
		mockMvc.perform(get(BASE + "/" + terminatedId + "/contract").header("Authorization", tokenHeader()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
		for (String suffix : new String[]{"/contracts", "/bundles", "/addons", "/roamings"}) {
			mockMvc.perform(get(BASE + "/" + terminatedId + suffix).header("Authorization", tokenHeader()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
		}
		User noSubscription = createUser(UserRole.USER);
		mockMvc.perform(get(BASE).header("Authorization", "Bearer " + jwtUtil.createAccessToken(noSubscription)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/plan", "/usage-records", "/billing-records", "/contract", "/contracts", "/bundles", "/addons", "/roamings"})
	@DisplayName("다른 사람의 회선은 userId를 위조해도 모든 조회 API에서 동일한 404로 차단한다")
	void rejectsOtherUsersSubscription(String suffix) throws Exception {
		mockMvc.perform(get(route(suffix)).header("Authorization", "Bearer " + jwtUtil.createAccessToken(other))
				.param("userId", owner.getId().toString()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUB-001"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "/plan", "/usage-records", "/billing-records", "/contract", "/contracts", "/bundles", "/addons", "/roamings"})
	@DisplayName("존재하지 않는 회선도 타인 회선과 동일한 404를 반환한다")
	void rejectsUnknownSubscription(String suffix) throws Exception {
		mockMvc.perform(get(BASE + "/" + Long.MAX_VALUE + suffix).header("Authorization", tokenHeader()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUB-001"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"list", "", "/plan", "/usage-records", "/billing-records", "/contract", "/contracts", "/bundles", "/addons", "/roamings"})
	@DisplayName("로그인하지 않은 사용자는 통신정보 조회에 접근할 수 없다")
	void rejectsAnonymous(String suffix) throws Exception {
		mockMvc.perform(get(suffix.equals("list") ? BASE : route(suffix)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"));
	}

	@Test
	@DisplayName("관리자도 MY API에서는 본인 회선만 조회한다")
	void doesNotBypassOwnershipForAdministrator() throws Exception {
		User administrator = createUser(UserRole.ADMIN);
		mockMvc.perform(get(route("/plan")).header("Authorization", "Bearer " + jwtUtil.createAccessToken(administrator)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUB-001"));
	}

	@Test
	@DisplayName("컨트롤러 없이 도구가 조회 서비스를 호출해도 타인 회선은 차단된다")
	void validatesOwnershipInsideServices() {
		for (Runnable lookup : new Runnable[]{
				() -> subscriptionService.getMySubscription(other.getId(), subscriptionId),
				() -> subscriptionService.getMyPlan(other.getId(), subscriptionId),
				() -> subscriptionService.getMyBundleList(other.getId(), subscriptionId),
				() -> subscriptionService.getMyAddonList(other.getId(), subscriptionId),
				() -> subscriptionService.getMyRoamingList(other.getId(), subscriptionId),
				() -> usageService.getMyUsageRecordList(other.getId(), subscriptionId, YearMonth.of(2026, 10), 0, 20),
				() -> billingService.getMyBillingRecordList(other.getId(), subscriptionId, null, 0, 20),
				() -> contractService.getMyContract(other.getId(), subscriptionId),
				() -> contractService.getMyContractList(other.getId(), subscriptionId)}) {
			assertThatThrownBy(lookup::run).isInstanceOf(SubscriptionException.class);
		}
	}

	@Test
	@DisplayName("조회 서비스는 HTTP 요청 문맥 없이 인증 주체 ID만으로 DTO를 반환한다")
	void reusesServicesWithoutHttpContext() {
		assertThat(subscriptionService.getMySubscription(owner.getId(), subscriptionId).subscriptionId()).isEqualTo(subscriptionId);
		assertThat(usageService.getMyUsageRecordList(owner.getId(), subscriptionId, YearMonth.of(2026, 10), 0, 20)
				.content().getFirst().remainingDataMb()).isEqualTo(4000L);
		assertThat(billingService.getMyBillingRecordList(owner.getId(), subscriptionId, null, 0, 20)
				.content()).hasSize(2);
		assertThat(contractService.getMyContract(owner.getId(), subscriptionId).installment().deviceName()).isEqualTo("테스트 단말");
	}

	@Test
	@DisplayName("탈퇴 사용자는 서버에 남은 사용자 ID로도 통신정보를 조회할 수 없다")
	void rejectsDeletedUserInsideServices() {
		jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE user_id = ?", owner.getId());
		assertThat(subscriptionService.getMySubscriptionList(owner.getId())).isEmpty();
		assertThatThrownBy(() -> subscriptionService.getMyPlan(owner.getId(), subscriptionId))
				.isInstanceOf(SubscriptionException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-00", "2026-13", "2026-1", "wrong", "2026-10-01", "0000-10"})
	@DisplayName("유효하지 않은 조회 월은 사용량·청구 모두 400으로 거절한다")
	void rejectsInvalidMonth(String month) throws Exception {
		for (String suffix : new String[]{"/usage-records", "/billing-records"}) {
			mockMvc.perform(get(route(suffix)).header("Authorization", tokenHeader()).param("month", month))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G-002"));
		}
	}

	@ParameterizedTest
	@CsvSource({"/usage-records,page,-1", "/usage-records,size,0", "/usage-records,size,101",
			"/billing-records,page,-1", "/billing-records,size,0", "/billing-records,size,101"})
	@DisplayName("페이지 번호와 조회 크기의 범위를 검증한다")
	void rejectsInvalidPage(String suffix, String name, String value) throws Exception {
		mockMvc.perform(get(route(suffix)).header("Authorization", tokenHeader()).param(name, value))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G-002"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "invalid"})
	@DisplayName("잘못된 회선 ID는 400으로 거절한다")
	void rejectsInvalidSubscriptionId(String id) throws Exception {
		mockMvc.perform(get(BASE + "/" + id).header("Authorization", tokenHeader()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G-002"));
	}

	private User createUser(UserRole role) {
		LocalDateTime now = LocalDateTime.now();
		return userRepository.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.com")
				.hashedPassword("test-only").name("조회 테스트 사용자").role(role).createdAt(now).updatedAt(now).build());
	}

	private long createSubscription(long userId, long planId, String state, String activatedAt) {
		return jdbc.queryForObject("""
				INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at, terminated_at)
				VALUES (?, ?, ?, ?, ?::timestamp, CASE WHEN ? = 'TERMINATED' THEN TIMESTAMP '2025-01-01' ELSE NULL END)
				RETURNING subscription_id
				""", Long.class, userId, planId, UUID.randomUUID().toString().substring(0, 30), state, activatedAt, state);
	}

	private void seedMonthlyRecords() {
		jdbc.update("""
				INSERT INTO usage_records (subscription_id, billing_month, data_used_mb, voice_used_minutes,
				    sms_used_count, allowance_snapshot, aggregated_through_date)
				VALUES (?, DATE '2026-10-01', 1000, 80, 10,
				    '{"dataAmountMb":5000,"dataUnlimited":false,"voiceMinutes":100,"voiceUnlimited":false,
				      "smsCount":50,"smsUnlimited":false,"internalNote":"노출 금지"}'::jsonb, DATE '2026-10-10')
				""", subscriptionId);
		jdbc.update("""
				INSERT INTO usage_records (subscription_id, billing_month, data_used_mb, voice_used_minutes,
				    sms_used_count, allowance_snapshot, aggregated_through_date, finalized_at, full_month_active)
				VALUES (?, DATE '2026-09-01', 2000, 80, 10,
				    '{"dataAmountMb":1000,"dataUnlimited":false,"voiceUnlimited":true,"smsCount":50,"smsUnlimited":false}'::jsonb,
				    DATE '2026-09-30', TIMESTAMP '2026-10-01', false)
				""", subscriptionId);
		jdbc.update("INSERT INTO usage_records (subscription_id, billing_month, data_used_mb) VALUES (?, DATE '2026-08-01', 1234)", subscriptionId);
		jdbc.update("""
				INSERT INTO billing_records (subscription_id, billing_month, base_fee, addon_fee, roaming_fee,
				    extra_fee, discount_amount, total_amount, due_date, payment_status, paid_amount, payment_method, paid_at)
				VALUES (?, DATE '2026-10-01', 40000, 1000, 2000, 3000, 10000, 36000, DATE '2026-10-25',
				    'PAID', 36000, 'AUTO_TRANSFER', TIMESTAMP '2026-10-20')
				""", subscriptionId);
		jdbc.update("""
				INSERT INTO billing_records (subscription_id, billing_month, base_fee, discount_amount, total_amount)
				VALUES (?, DATE '2026-09-01', 40000, 50000, 0)
				""", subscriptionId);
	}

	private void seedContracts() {
		jdbc.update("""
				INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, discount_amount,
				    device_name, device_total_amount, installment_months, paid_installment_months, monthly_installment_amount,
				    remaining_installment_amount, installment_started_at, installment_expires_at, installment_status)
				VALUES (?, 'DEVICE_SUBSIDY', DATE '2026-01-01', DATE '2027-12-31', 5000, '테스트 단말',
				    1200000, 24, 9, 50000, 750000, DATE '2026-01-01', DATE '2027-12-31', 'ACTIVE')
				""", subscriptionId);
		jdbc.update("""
				INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, status)
				VALUES (?, 'SELECTIVE_CONTRACT', DATE '2024-01-01', DATE '2025-12-31', 'EXPIRED')
				""", subscriptionId);
		jdbc.update("""
				INSERT INTO subscription_contracts (subscription_id, contract_type, started_at, expires_at, status, canceled_at)
				VALUES (?, 'SELECTIVE_CONTRACT', DATE '2023-01-01', DATE '2023-12-31', 'CANCELED', TIMESTAMP '2023-09-01')
				""", subscriptionId);
	}

	private void seedJoinedProducts() {
		long bundleId = jdbc.queryForObject("""
				INSERT INTO bundle_products (code, name, discount_amount, status)
				VALUES (?, '현재 결합 이름', 20000, 'INACTIVE') RETURNING bundle_product_id
				""", Long.class, UUID.randomUUID().toString());
		jdbc.update("""
				INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at, product_name_snapshot, applied_discount_amount)
				VALUES (?, ?, TIMESTAMP '2026-01-01', '가입 당시 결합', 7000)
				""", subscriptionId, bundleId);
		jdbc.update("""
				INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at, ended_at,
				    status, product_name_snapshot, applied_discount_amount)
				VALUES (?, ?, TIMESTAMP '2024-01-01', TIMESTAMP '2025-01-01', 'ENDED', '이전 결합', 5000)
				""", subscriptionId, bundleId);
		long addonId = jdbc.queryForObject("""
				INSERT INTO addon_services (code, name, monthly_fee, status)
				VALUES (?, '현재 부가서비스 이름', 9000, 'INACTIVE') RETURNING addon_service_id
				""", Long.class, UUID.randomUUID().toString());
		jdbc.update("""
				INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at, service_name_snapshot, monthly_fee_snapshot)
				VALUES (?, ?, TIMESTAMP '2026-01-01', '가입 당시 부가서비스', 1000)
				""", subscriptionId, addonId);
		jdbc.update("""
				INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at, canceled_at,
				    status, service_name_snapshot, monthly_fee_snapshot)
				VALUES (?, ?, TIMESTAMP '2024-01-01', TIMESTAMP '2025-01-01', 'CANCELED', '이전 부가서비스', 500)
				""", subscriptionId, addonId);
		long roamingId = jdbc.queryForObject("""
				INSERT INTO roaming_products (code, name, daily_fee, data_amount_mb, country, status)
				VALUES (?, '현재 로밍 이름', 15000, 99999, '일본', 'INACTIVE') RETURNING roaming_product_id
				""", Long.class, UUID.randomUUID().toString());
		for (String state : new String[]{"SCHEDULED", "ACTIVE", "EXPIRED", "CANCELED"}) {
			String date = switch (state) {
				case "SCHEDULED" -> "2026-11-01";
				case "ACTIVE" -> "2026-10-01";
				case "EXPIRED" -> "2025-01-01";
				default -> "2024-01-01";
			};
			jdbc.update("""
					INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date, end_date,
					    status, product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot, canceled_at)
					VALUES (?, ?, ?::date, ?::date, ?, '가입 당시 로밍', 5000, 5000,
					    CASE WHEN ? = 'CANCELED' THEN TIMESTAMP '2024-01-01' ELSE NULL END)
					""", subscriptionId, roamingId, date, date, state, state);
		}
	}

	private String route(String suffix) {
		return BASE + "/" + subscriptionId + suffix;
	}

	private String tokenHeader() {
		return "Bearer " + token;
	}
}
