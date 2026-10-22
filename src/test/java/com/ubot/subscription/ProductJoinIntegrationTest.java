package com.ubot.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
@DisplayName("사용자 상품 가입·해지·결합 통합 테스트")
class ProductJoinIntegrationTest {

	private static final String BASE = "/subscriptions/me/";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private EntityManager entityManager;

	private User owner;
	private User other;
	private User third;
	private User fourth;
	private long currentPlanId;
	private long generalPlanId;
	private long youthPlanId;
	private long seniorPlanId;
	private long ownerLine;
	private long suspendedLine;
	private long otherLine;
	private long thirdLine;
	private String otherPhone;
	private String thirdPhone;
	private String fourthPhone;

	@BeforeEach
	void createFixtures() {
		owner = createUser(LocalDate.now().minusYears(30));
		other = createUser(null);
		third = createUser(null);
		fourth = createUser(null);
		currentPlanId = createPlan(null, null);
		generalPlanId = createPlan(null, null);
		youthPlanId = createPlan(19, 34);
		seniorPlanId = createPlan(65, null);
		ownerLine = createSubscription(owner.getId(), randomPhone(), "ACTIVE");
		suspendedLine = createSubscription(owner.getId(), randomPhone(), "SUSPENDED");
		otherPhone = randomPhone();
		otherLine = createSubscription(other.getId(), otherPhone, "ACTIVE");
		thirdPhone = randomPhone();
		thirdLine = createSubscription(third.getId(), thirdPhone, "ACTIVE");
		fourthPhone = randomPhone();
		createSubscription(fourth.getId(), fourthPhone, "ACTIVE");
	}

	@Test
	@DisplayName("요금제는 나이 조건을 확인하고 한 달에 한 번만 바꾼다")
	void changesPlanOncePerMonth() throws Exception {
		send(patch(BASE + ownerLine + "/plan"), owner, "{\"planId\":" + seniorPlanId + "}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SUB-005"));
		send(patch(BASE + ownerLine + "/plan"), owner, "{\"planId\":" + currentPlanId + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-004"));
		send(patch(BASE + ownerLine + "/plan"), owner, "{\"planId\":" + youthPlanId + "}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.summary.planId").value(youthPlanId));
		mockMvc.perform(get(BASE + ownerLine + "/plan").with(as(owner)))
				.andExpect(jsonPath("$.data.summary.planId").value(youthPlanId));
		send(patch(BASE + ownerLine + "/plan"), owner, "{\"planId\":" + generalPlanId + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-003"));
		send(patch(BASE + otherLine + "/plan"), owner, "{\"planId\":" + generalPlanId + "}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUB-001"));
		send(patch(BASE + suspendedLine + "/plan"), owner, "{\"planId\":" + generalPlanId + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-002"));
	}

	@Test
	@DisplayName("부가서비스는 이용 중인 회선에서 한 번만 가입하고 해지한다")
	void subscribesAndCancelsAddon() throws Exception {
		long video = productId("addon_services", "addon_service_id", "UBOT_ADDON_VIDEO");
		String body = "{\"addonServiceId\":" + video + "}";
		long recordId = id(send(post(BASE + ownerLine + "/addons"), owner, body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("Ubot 비디오패스"))
				.andExpect(jsonPath("$.data.monthlyFee").value(9900))
				.andExpect(jsonPath("$.data.status").value("ACTIVE")), "subscriptionAddonId");
		send(post(BASE + ownerLine + "/addons"), owner, body)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-006"));
		send(post(BASE + suspendedLine + "/addons"), owner, body)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-002"));

		mockMvc.perform(delete(BASE + ownerLine + "/addons/" + recordId).with(as(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELED"));
		mockMvc.perform(delete(BASE + ownerLine + "/addons/" + recordId).with(as(owner)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUB-007"));

		// 이 테스트 트랜잭션에서 아직 읽지 않은 상품으로 판매 중지를 확인한다.
		long music = productId("addon_services", "addon_service_id", "UBOT_ADDON_MUSIC");
		jdbc.update("UPDATE addon_services SET status = 'INACTIVE' WHERE addon_service_id = ?", music);
		send(post(BASE + ownerLine + "/addons"), owner, "{\"addonServiceId\":" + music + "}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ADDON-001"));
	}

	@Test
	@DisplayName("로밍은 오늘 이후 최대 90일로 예약하고 사용 시작 전에만 취소한다")
	void schedulesAndCancelsRoaming() throws Exception {
		long japan = productId("roaming_products", "roaming_product_id", "UBOT_ROAM_JP_1G");
		LocalDate tomorrow = LocalDate.now().plusDays(1);
		send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(japan, LocalDate.now().minusDays(1), tomorrow))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SUB-008"));
		send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(japan, tomorrow, tomorrow.plusDays(90)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SUB-008"));
		long recordId = id(send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(japan, tomorrow, tomorrow.plusDays(3)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.country").value("일본"))
				.andExpect(jsonPath("$.data.status").value("SCHEDULED")), "subscriptionRoamingId");

		mockMvc.perform(delete(BASE + ownerLine + "/roamings/" + recordId).with(as(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("CANCELED"));
		mockMvc.perform(delete(BASE + ownerLine + "/roamings/" + recordId).with(as(owner)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-010"));

		long todayId = id(send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(japan, LocalDate.now(), tomorrow))
				.andExpect(status().isCreated()), "subscriptionRoamingId");
		mockMvc.perform(delete(BASE + ownerLine + "/roamings/" + todayId).with(as(owner)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-010"));
	}

	@Test
	@DisplayName("같은 회선에서 예약·이용 중인 로밍과 기간이 겹치면 국가와 관계없이 예약할 수 없다")
	void rejectsOverlappingRoaming() throws Exception {
		long japan = productId("roaming_products", "roaming_product_id", "UBOT_ROAM_JP_1G");
		long china = productId("roaming_products", "roaming_product_id", "UBOT_ROAM_CN_1G");
		LocalDate start = LocalDate.now().plusDays(10);
		long reservedId = id(send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(japan, start, start.plusDays(4)))
				.andExpect(status().isCreated()), "subscriptionRoamingId");

		// 종료일 하루만 겹쳐도 막고, 다른 국가 상품도 막는다.
		send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(japan, start.plusDays(4), start.plusDays(6)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-017"));
		send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(china, start.minusDays(2), start))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-017"));
		// 바로 다음 날부터는 예약할 수 있고, 다른 회선은 영향을 받지 않는다.
		send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(china, start.plusDays(5), start.plusDays(6)))
				.andExpect(status().isCreated());
		send(post(BASE + otherLine + "/roamings"), other, roamingBody(japan, start, start.plusDays(4)))
				.andExpect(status().isCreated());

		// 취소한 예약의 기간은 다시 예약할 수 있다.
		mockMvc.perform(delete(BASE + ownerLine + "/roamings/" + reservedId).with(as(owner)))
				.andExpect(status().isOk());
		send(post(BASE + ownerLine + "/roamings"), owner, roamingBody(china, start, start.plusDays(4)))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("인터넷/IPTV는 종류마다 하나만 가입하고 해지한다")
	void subscribesHomeProducts() throws Exception {
		long internet = productId("home_products", "home_product_id", "UBOT_HOME_INTERNET_100");
		long iptv = productId("home_products", "home_product_id", "UBOT_HOME_IPTV_BASIC");
		long recordId = id(send(post("/home-subscriptions/me"), owner, "{\"homeProductId\":" + internet + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.productType").value("INTERNET"))
				.andExpect(jsonPath("$.data.monthlyFee").value(22000)), "homeSubscriptionId");
		send(post("/home-subscriptions/me"), owner, "{\"homeProductId\":" + iptv + "}")
				.andExpect(status().isCreated());
		send(post("/home-subscriptions/me"), owner, "{\"homeProductId\":" + internet + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("HSUB-002"));
		mockMvc.perform(get("/home-subscriptions/me").with(as(owner)))
				.andExpect(jsonPath("$.data.length()").value(2));
		mockMvc.perform(get("/home-subscriptions/me").with(as(other)))
				.andExpect(jsonPath("$.data.length()").value(0));

		mockMvc.perform(delete("/home-subscriptions/me/" + recordId).with(as(other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("HSUB-001"));
		mockMvc.perform(delete("/home-subscriptions/me/" + recordId).with(as(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ENDED"));
		send(post("/home-subscriptions/me"), owner, "{\"homeProductId\":" + internet + "}")
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("가족 결합은 대표가 전화번호로 회선을 추가하고, 회선 수를 채우면 대표 회선에 할인한다")
	void managesFamilyBundleByPhoneNumber() throws Exception {
		long family3 = productId("bundle_products", "bundle_product_id", "UBOT_BUNDLE_FAMILY_3");
		long groupId = id(send(post(BASE + ownerLine + "/bundles"), owner, "{\"bundleProductId\":" + family3 + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.bundleType").value("FAMILY"))
				.andExpect(jsonPath("$.data.lineCount").value(1))
				.andExpect(jsonPath("$.data.requiredLineCount").value(3))
				.andExpect(jsonPath("$.data.discountApplied").value(false))
				.andExpect(jsonPath("$.data.discountAmount").value(0)), "bundleGroupId");
		String members = BASE + ownerLine + "/bundle-groups/" + groupId + "/members";
		String otherMembers = BASE + otherLine + "/bundle-groups/" + groupId + "/members";

		send(post(members), owner, phoneBody(hyphenate(otherPhone)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.lineCount").value(2))
				.andExpect(jsonPath("$.data.discountApplied").value(false));
		send(post(members), owner, phoneBody(otherPhone))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SUB-013"));
		send(post(members), owner, phoneBody("01900000000"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SUB-013"));
		send(post(members), other, phoneBody(thirdPhone))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUB-012"));
		send(post(otherMembers), other, phoneBody(thirdPhone))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUB-012"));
		send(post(BASE + otherLine + "/bundles"), other, "{\"bundleProductId\":" + family3 + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-011"));
		send(post(members), owner, phoneBody(thirdPhone))
				.andExpect(jsonPath("$.data.lineCount").value(3))
				.andExpect(jsonPath("$.data.discountApplied").value(true))
				.andExpect(jsonPath("$.data.discountAmount").value(15000));
		send(post(members), owner, phoneBody(fourthPhone))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-014"));

		// 다른 사람이 추가한 결합도 내 회선에서 보이고, 번호는 마스킹된다. 할인액은 대표 회선 기록에만 있다.
		String otherView = mockMvc.perform(get(BASE + otherLine + "/bundles").with(as(other)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.data[0].appliedDiscountAmount").value(0))
				.andExpect(jsonPath("$.data[0].group.bundleGroupId").value(groupId))
				.andExpect(jsonPath("$.data[0].group.members.length()").value(3))
				.andExpect(jsonPath("$.data[0].group.members[0].subscriptionId").value(ownerLine))
				.andExpect(jsonPath("$.data[0].group.members[0].representative").value(true))
				.andReturn().getResponse().getContentAsString();
		assertThat(otherView).doesNotContain(thirdPhone);
		mockMvc.perform(get(BASE + ownerLine + "/bundles").with(as(owner)))
				.andExpect(jsonPath("$.data[0].appliedDiscountAmount").value(15000));

		mockMvc.perform(delete(otherMembers + "/" + thirdLine).with(as(other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUB-015"));
		mockMvc.perform(delete(otherMembers + "/" + otherLine).with(as(other)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.lineCount").value(2))
				.andExpect(jsonPath("$.data.discountApplied").value(false));
		mockMvc.perform(delete(members + "/" + ownerLine).with(as(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").doesNotExist());

		mockMvc.perform(get(BASE + thirdLine + "/bundles").with(as(third)))
				.andExpect(jsonPath("$.data[0].status").value("ENDED"))
				.andExpect(jsonPath("$.data[0].group").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT status FROM bundle_groups WHERE bundle_group_id = ?", String.class, groupId))
				.isEqualTo("ENDED");
	}

	@Test
	@DisplayName("홈 결합은 회선을 추가할 수 없고, 대표 명의자가 연결된 인터넷을 이용할 때만 할인한다")
	void appliesHomeBundleDiscountByInternet() throws Exception {
		long home100 = productId("bundle_products", "bundle_product_id", "UBOT_BUNDLE_HOME_100");
		long groupId = id(send(post(BASE + ownerLine + "/bundles"), owner, "{\"bundleProductId\":" + home100 + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.requiredLineCount").value(1))
				.andExpect(jsonPath("$.data.discountApplied").value(false)), "bundleGroupId");
		send(post(BASE + ownerLine + "/bundle-groups/" + groupId + "/members"), owner, phoneBody(otherPhone))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-014"));

		// 다른 속도의 인터넷으로는 할인하지 않는다.
		long internet500 = productId("home_products", "home_product_id", "UBOT_HOME_INTERNET_500");
		long homeId = id(send(post("/home-subscriptions/me"), owner, "{\"homeProductId\":" + internet500 + "}")
				.andExpect(status().isCreated()), "homeSubscriptionId");
		mockMvc.perform(get(BASE + ownerLine + "/bundles").with(as(owner)))
				.andExpect(jsonPath("$.data[0].group.discountApplied").value(false));

		mockMvc.perform(delete("/home-subscriptions/me/" + homeId).with(as(owner))).andExpect(status().isOk());
		long internet100 = productId("home_products", "home_product_id", "UBOT_HOME_INTERNET_100");
		send(post("/home-subscriptions/me"), owner, "{\"homeProductId\":" + internet100 + "}")
				.andExpect(status().isCreated());
		mockMvc.perform(get(BASE + ownerLine + "/bundles").with(as(owner)))
				.andExpect(jsonPath("$.data[0].group.discountApplied").value(true))
				.andExpect(jsonPath("$.data[0].group.discountAmount").value(3300));
	}

	@Test
	@DisplayName("회선 수가 정해지지 않은 결합은 2회선부터 할인한다")
	void appliesDiscountFromTwoLinesWithoutLimit() throws Exception {
		long longTerm = productId("bundle_products", "bundle_product_id", "UBOT_BUNDLE_LONG_TERM");
		long groupId = id(send(post(BASE + ownerLine + "/bundles"), owner, "{\"bundleProductId\":" + longTerm + "}")
				.andExpect(jsonPath("$.data.requiredLineCount").doesNotExist())
				.andExpect(jsonPath("$.data.discountApplied").value(false)), "bundleGroupId");
		send(post(BASE + ownerLine + "/bundle-groups/" + groupId + "/members"), owner, phoneBody(otherPhone))
				.andExpect(jsonPath("$.data.discountApplied").value(true))
				.andExpect(jsonPath("$.data.discountAmount").value(10000));
		send(post(BASE + ownerLine + "/bundle-groups/" + groupId + "/members"), owner, phoneBody(thirdPhone))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.lineCount").value(3));
	}

	@Test
	@DisplayName("요금제에 포함된 부가서비스는 0원으로 들어오고, 따로 가입하거나 해지할 수 없으며 요금제를 바꾸면 함께 바뀐다")
	void syncsPlanIncludedAddons() throws Exception {
		long music = productId("addon_services", "addon_service_id", "UBOT_ADDON_MUSIC");
		long video = productId("addon_services", "addon_service_id", "UBOT_ADDON_VIDEO");
		long phishing = productId("addon_services", "addon_service_id", "UBOT_ADDON_PHISHING");
		long nextPlanId = createPlan(null, null);
		includeAddons(generalPlanId, music, video);
		includeAddons(nextPlanId, video, phishing);

		// 따로 가입해 둔 부가서비스가 새 요금제에 포함되면 해지하고 0원 포함으로 바꾼다.
		long paidVideoId = id(send(post(BASE + ownerLine + "/addons"), owner, "{\"addonServiceId\":" + video + "}")
				.andExpect(status().isCreated()), "subscriptionAddonId");
		send(patch(BASE + ownerLine + "/plan"), owner, "{\"planId\":" + generalPlanId + "}")
				.andExpect(status().isOk());
		mockMvc.perform(get(BASE + ownerLine + "/addons").with(as(owner)))
				.andExpect(jsonPath("$.data[?(@.subscriptionAddonId == %d)].status".formatted(paidVideoId)).value("CANCELED"))
				.andExpect(jsonPath("$.data[?(@.addonServiceId == %d && @.status == 'ACTIVE')].monthlyFee".formatted(video)).value(0))
				.andExpect(jsonPath("$.data[?(@.addonServiceId == %d && @.status == 'ACTIVE')].monthlyFee".formatted(music)).value(0));
		assertThat(activeAddonSources(ownerLine)).containsExactlyInAnyOrder(
				music + ":PLAN_INCLUDED", video + ":PLAN_INCLUDED");

		// 포함된 부가서비스는 따로 가입하거나 해지할 수 없다.
		send(post(BASE + ownerLine + "/addons"), owner, "{\"addonServiceId\":" + music + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-006"));
		long includedMusicId = activeAddonId(ownerLine, music);
		mockMvc.perform(delete(BASE + ownerLine + "/addons/" + includedMusicId).with(as(owner)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUB-016"));

		// 요금제를 바꾸면 이전 요금제에만 있던 것은 해지되고, 두 요금제에 모두 있는 것은 그대로 남는다.
		long includedVideoId = activeAddonId(ownerLine, video);
		// 요청들이 테스트 트랜잭션의 영속성 컨텍스트를 함께 쓰므로, 변경 시각을 직접 고친 뒤 캐시를 비운다.
		entityManager.flush();
		jdbc.update("UPDATE subscriptions SET plan_changed_at = plan_changed_at - INTERVAL '2 months' WHERE subscription_id = ?",
				ownerLine);
		entityManager.clear();
		send(patch(BASE + ownerLine + "/plan"), owner, "{\"planId\":" + nextPlanId + "}")
				.andExpect(status().isOk());
		mockMvc.perform(get(BASE + ownerLine + "/addons").with(as(owner)))
				.andExpect(jsonPath("$.data[?(@.subscriptionAddonId == %d)].status".formatted(includedMusicId)).value("CANCELED"));
		assertThat(activeAddonSources(ownerLine)).containsExactlyInAnyOrder(
				video + ":PLAN_INCLUDED", phishing + ":PLAN_INCLUDED");
		assertThat(activeAddonId(ownerLine, video)).isEqualTo(includedVideoId);
	}

	private void includeAddons(long planId, long... addonServiceIds) {
		for (long addonServiceId : addonServiceIds) {
			jdbc.update("INSERT INTO plan_included_addons (plan_id, addon_service_id) VALUES (?, ?)", planId, addonServiceId);
		}
	}

	private List<String> activeAddonSources(long subscriptionId) {
		return jdbc.queryForList("""
				SELECT addon_service_id || ':' || source FROM subscription_addons
				WHERE subscription_id = ? AND status = 'ACTIVE'
				""", String.class, subscriptionId);
	}

	private long activeAddonId(long subscriptionId, long addonServiceId) {
		return jdbc.queryForObject("""
				SELECT subscription_addon_id FROM subscription_addons
				WHERE subscription_id = ? AND addon_service_id = ? AND status = 'ACTIVE'
				""", Long.class, subscriptionId, addonServiceId);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, User actor, String body) throws Exception {
		return mockMvc.perform(request.with(as(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private RequestPostProcessor as(User actor) {
		return user(new CustomUserDetails(actor));
	}

	private long id(ResultActions result, String idField) throws Exception {
		String response = result.andReturn().getResponse().getContentAsString();
		return Long.parseLong(response.replaceAll(".*\"" + idField + "\":(\\d+).*", "$1"));
	}

	private long productId(String table, String idColumn, String code) {
		return jdbc.queryForObject("SELECT " + idColumn + " FROM " + table + " WHERE code = ?", Long.class, code);
	}

	private String roamingBody(long roamingProductId, LocalDate startDate, LocalDate endDate) {
		return "{\"roamingProductId\":%d,\"startDate\":\"%s\",\"endDate\":\"%s\"}"
				.formatted(roamingProductId, startDate, endDate);
	}

	private String phoneBody(String phoneNumber) {
		return "{\"phoneNumber\":\"" + phoneNumber + "\"}";
	}

	private String hyphenate(String digits) {
		return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
	}

	// 시드 회선(010-)과 겹치지 않는 번호
	private String randomPhone() {
		return "019" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
	}

	private User createUser(LocalDate birthDate) {
		LocalDateTime now = LocalDateTime.now();
		return userRepository.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.com")
				.hashedPassword("test-only").name("가입 테스트 사용자").role(UserRole.USER).birthDate(birthDate)
				.createdAt(now).updatedAt(now).build());
	}

	private long createPlan(Integer minAge, Integer maxAge) {
		return jdbc.queryForObject("""
				INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee,
				    data_amount_mb, voice_minutes, sms_count, min_age, max_age)
				VALUES (?, '가입 테스트 요금제', 'FIVE_G', 'GENERAL', 30000, 1000, 100, 100, ?, ?)
				RETURNING plan_id
				""", Long.class, UUID.randomUUID().toString().substring(0, 30), minAge, maxAge);
	}

	private long createSubscription(long userId, String phoneNumber, String state) {
		return jdbc.queryForObject("""
				INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at)
				VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP - INTERVAL '1 year')
				RETURNING subscription_id
				""", Long.class, userId, currentPlanId, phoneNumber, state);
	}
}
