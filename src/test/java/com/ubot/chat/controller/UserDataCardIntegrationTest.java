package com.ubot.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 질문 요청부터 화면이 받는 최종 JSON까지 가입 정보 카드(userData)가 실리는지 확인합니다.
 * 실제 인증 필터·Controller·ChatService·UserIntentHandler·UserDataSummarizer·AiService·UserDataTools·JPA·임시 PostgreSQL을 쓰고,
 * FAQ 검색과 외부 LLM 통신만 mock입니다. 도구 호출은 LLM mock이 요청에 실린 실제 도구 콜백을 실행해 흉내 냅니다.
 */
// 사용자 정보 섹션을 넣을 {{context}} 자리가 있어야 하므로 테스트용이 아닌 실제 프롬프트 파일을 씁니다.
@SpringBootTest(properties = {
		"CHAT_TOP_K=3",
		"CHAT_CONFIDENCE_THRESHOLD=0.75"})
@Import({PgvectorTestConfiguration.class, UserDataCardIntegrationTest.FixedClockConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserDataCardIntegrationTest {

	// 사용자 A(회선 1개)와 B(회선 2개)의 12개월 사용량·청구서가 들어 있는 평가용 데이터입니다. 기준일은 2026-10-12입니다.
	private static final String SEED = "evaluation/user_data_seed.sql";
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final String USER_A_PHONE = "010-2468-1357";
	private static final String USER_A_SIM = "8982051234567891357";

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(LocalDateTime.of(2026, 10, 12, 10, 0).atZone(SEOUL).toInstant(), SEOUL);
		}
	}

	@Autowired MockMvc mvc;
	@Autowired JwtUtil jwt;
	@Autowired JdbcTemplate jdbc;
	@Autowired UserRepository users;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired QuestionLogRepository questions;
	@Autowired FaqLogRepository faqLogs;
	@Autowired UnansweredQuestionRepository unansweredQuestions;
	@Autowired UnansweredQuestionGroupRepository unansweredQuestionGroups;
	@MockitoBean FaqVectorService vector;
	@MockitoBean LlmClient llm;

	String userA;
	String userB;

	@BeforeEach
	void setUp() {
		unansweredQuestions.deleteAllInBatch();
		unansweredQuestionGroups.deleteAllInBatch();
		faqLogs.deleteAllInBatch();
		questions.deleteAllInBatch();
		attempts.deleteAllInBatch();
		Integer seeded = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'eval-a@ubot.test'", Integer.class);
		if (seeded == null || seeded == 0) {
			new ResourceDatabasePopulator(new ClassPathResource(SEED)).execute(jdbc.getDataSource());
		}
		userA = bearer("eval-a@ubot.test");
		userB = bearer("eval-b@ubot.test");
		givenFaq(Intent.USER_DATA);
	}

	@Test
	void 요약으로_답한_청구서를_카드로_보낸다() throws Exception {
		when(llm.generateAnswer(any())).thenReturn(answer("이번 달 요금은 2026년 10월 청구서 기준 61,950원입니다."));

		ResultActions result = ask(userA, "이번 달 요금 얼마야?");

		result.andExpect(jsonPath("$.data.answer").value("이번 달 요금은 2026년 10월 청구서 기준 61,950원입니다."))
				.andExpect(jsonPath("$.data.userData.billings.length()").value(1))
				.andExpect(jsonPath("$.data.userData.billings[0].phoneNumber").value("010-****-1357"))
				.andExpect(jsonPath("$.data.userData.billings[0].usageMonth").value(9))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.billingMonth").value("2026-10"))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.totalAmount").value(61950))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.paymentStatus").value("UNPAID"))
				.andExpect(jsonPath("$.data.userData.plans.length()").value(0))
				.andExpect(jsonPath("$.data.userData.usages.length()").value(0))
				.andExpect(jsonPath("$.data.store").doesNotExist());
		assertNoPrivateData(result);
	}

	@Test
	void 도구로_조회한_청구서를_항목별_금액과_함께_카드로_보낸다() throws Exception {
		when(llm.generateAnswer(any())).thenAnswer(callingTool("getBillingByMonth", "2026-09",
				"2026년 9월 청구서(8월 사용분) 기준으로 안내드립니다. 총 61,950원입니다."));

		ResultActions result = ask(userA, "9월 청구서 보여줘");

		result.andExpect(jsonPath("$.data.userData.billings.length()").value(1))
				.andExpect(jsonPath("$.data.userData.billings[0].usageMonth").value(8))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.billingMonth").value("2026-09"))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.baseFee").value(75000))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.addonFee").value(11200))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.discountAmount").value(24250))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.totalAmount").value(61950))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.paymentStatus").value("PAID"));
		assertNoPrivateData(result);
	}

	@Test
	void 도구로_조회한_청구서와_답변에_쓰인_요약_청구서를_중복_없이_함께_보낸다() throws Exception {
		when(llm.generateAnswer(any())).thenAnswer(callingTool("getBillingByMonth", "2026-09",
				"2026년 9월 청구서와 2026년 10월 청구서는 모두 61,950원이라 차이가 없습니다."));

		ResultActions result = ask(userA, "지난달이랑 이번 달 요금 차이가 얼마야?");

		result.andExpect(jsonPath("$.data.userData.billings.length()").value(2))
				.andExpect(jsonPath("$.data.userData.billings[0].billing.billingMonth").value("2026-09"))
				.andExpect(jsonPath("$.data.userData.billings[1].billing.billingMonth").value("2026-10"));
	}

	@Test
	void 도구로_조회한_사용량을_카드로_보낸다() throws Exception {
		when(llm.generateAnswer(any())).thenAnswer(callingTool("getUsageByMonth", "2026-09",
				"지난달(2026년 9월) 데이터는 53.5GB 사용하셨습니다."));

		ResultActions result = ask(userA, "지난달 데이터 얼마나 썼어?");

		result.andExpect(jsonPath("$.data.userData.usages.length()").value(1))
				.andExpect(jsonPath("$.data.userData.usages[0].phoneNumber").value("010-****-1357"))
				.andExpect(jsonPath("$.data.userData.usages[0].usage.billingMonth").value("2026-09"))
				.andExpect(jsonPath("$.data.userData.usages[0].usage.dataUsedMb").value(54770))
				.andExpect(jsonPath("$.data.userData.usages[0].usage.remainingDataMb").value(153600 - 54770))
				.andExpect(jsonPath("$.data.userData.usages[0].usage.voiceUsedMinutes").value(221))
				.andExpect(jsonPath("$.data.userData.usages[0].usage.smsUsedCount").value(16));
		assertNoPrivateData(result);
	}

	@Test
	void 요금제_질문이면_이름_없이_답해도_요금제_카드를_보낸다() throws Exception {
		when(llm.generateAnswer(any())).thenReturn(answer("네, 통화는 무제한입니다."));

		ResultActions result = ask(userA, "제 요금제 통화 무제한인가요?");

		result.andExpect(jsonPath("$.data.userData.plans.length()").value(1))
				.andExpect(jsonPath("$.data.userData.plans[0].phoneNumber").value("010-****-1357"))
				.andExpect(jsonPath("$.data.userData.plans[0].plan.summary.name").value("5G 스탠다드"))
				.andExpect(jsonPath("$.data.userData.plans[0].plan.summary.monthlyFee").value(75000))
				.andExpect(jsonPath("$.data.userData.plans[0].plan.summary.voiceUnlimited").value(true))
				.andExpect(jsonPath("$.data.userData.plans[0].plan.tetheringAmountMb").value(30720));
		assertNoPrivateData(result);
	}

	@Test
	void 회선이_여러_개면_답변에_나온_회선마다_카드를_보낸다() throws Exception {
		when(llm.generateAnswer(any())).thenReturn(answer("010-****-5678 회선은 5G 스탠다드, 010-****-0001 회선은 LTE 베이직입니다."));

		ResultActions result = ask(userB, "회선별 요금제 알려줘");

		result.andExpect(jsonPath("$.data.userData.plans.length()").value(2))
				.andExpect(jsonPath("$.data.userData.plans[*].phoneNumber").value(
						containsInAnyOrder("010-****-5678", "010-****-0001")))
				.andExpect(jsonPath("$.data.userData.plans[*].plan.summary.name").value(
						containsInAnyOrder("5G 스탠다드", "LTE 베이직")));
		assertThat(body(result)).doesNotContain("010-1234-5678", "010-9876-0001");
	}

	@Test
	void 가입_정보를_쓰지_않은_답변에는_카드를_보내지_않는다() throws Exception {
		when(llm.generateAnswer(any())).thenReturn(answer("소액결제 정보는 확인할 수 없습니다."));

		ask(userA, "소액결제 얼마 썼어?").andExpect(jsonPath("$.data.userData").doesNotExist());
	}

	@Test
	void 비회원에게는_카드를_보내지_않는다() throws Exception {
		when(llm.generateAnswer(any())).thenReturn(answer("로그인 후 5G 스탠다드 요금제를 확인할 수 있습니다."));

		complete(post("/chat/questions").session(new MockHttpSession()).contentType("application/json")
				.content("{\"question\":\"내 요금제 뭐야?\"}"))
				.andExpect(jsonPath("$.data.userData").doesNotExist());
	}

	@Test
	void 가입_정보가_아닌_의도에는_카드를_보내지_않는다() throws Exception {
		givenFaq(Intent.GENERAL);
		when(llm.generateAnswer(any())).thenReturn(answer("5G 스탠다드 요금제는 매장에서 바꿀 수 있습니다."));

		ask(userA, "요금제 변경 방법 알려줘").andExpect(jsonPath("$.data.userData").doesNotExist());
	}

	/** LLM mock이 요청에 실린 실제 도구 콜백을 실행합니다. Spring AI가 도구를 부를 때와 같은 경로로 DB를 조회하고 recorder에 남깁니다. */
	private Answer<LlmResponseDto> callingTool(String toolName, String month, String text) {
		return invocation -> {
			LlmRequestDto request = invocation.getArgument(0);
			request.toolCallbacks().stream()
					.filter(callback -> callback.getToolDefinition().name().equals(toolName))
					.findFirst().orElseThrow()
					.call("{\"month\":\"" + month + "\"}", new ToolContext(request.toolContext()));
			return answer(text);
		};
	}

	private static LlmResponseDto answer(String text) {
		return new LlmResponseDto("{\"answer\":\"" + text + "\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");
	}

	private void givenFaq(Intent intent) {
		Long category = jdbc.queryForObject(
				"insert into faq_category(name) values (?) returning id", Long.class, UUID.randomUUID().toString());
		Long faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
				Long.class, category, "가입 정보 조회", "가입 정보 안내");
		when(vector.getSimilarList(anyString(), anyInt()))
				.thenReturn(List.of(new FaqSearchResponseDto(faqId, "가입 정보 조회", "가입 정보 안내", 0.9, intent)));
	}

	private String bearer(String email) {
		return "Bearer " + jwt.createAccessToken(users.findByEmailAndDeletedAtIsNull(email).orElseThrow());
	}

	private ResultActions ask(String authorization, String question) throws Exception {
		return complete(post("/chat/questions").header("Authorization", authorization).contentType("application/json")
				.content("{\"question\":\"" + question + "\"}"));
	}

	private ResultActions complete(MockHttpServletRequestBuilder builder) throws Exception {
		var pending = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
		pending.getAsyncResult(10_000);
		return mvc.perform(asyncDispatch(pending))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("SUCCESS"));
	}

	/** 카드에는 마스킹된 번호만 실리고, 원본 번호·SIM 식별자·이메일은 응답 어디에도 없어야 합니다. */
	private void assertNoPrivateData(ResultActions result) throws Exception {
		assertThat(body(result)).doesNotContain(USER_A_PHONE, "01024681357", USER_A_SIM, "eval-a@ubot.test",
				"maskedSimIdentifier", "simIdentifier");
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}
}
