package com.ubot.chat.controller;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.chat.service.ChatService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import jakarta.servlet.AsyncEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 인증 필터·기존 Controller/Service/Prompt/LLM 서비스·JPA·임시 PostgreSQL을 연결합니다.
 * FAQ 검색의 반환값과 외부 LLM 통신만 mock이며 개발 DB와 실제 LLM은 사용하지 않습니다.
 */
@SpringBootTest(properties = {
		"prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
		"prompt.faq.user-location=classpath:prompts/test-faq-user.txt",
		"CHAT_TOP_K=3",
		"CHAT_CONFIDENCE_THRESHOLD=0.75",
		"CHAT_MAX_ATTEMPTS=5"})
@Import(PgvectorTestConfiguration.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatLlmIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired ChatService service;
	@Autowired ChatAttemptsService attemptsService;
	@Autowired JwtUtil jwt;
	@Autowired UserRepository users;
	@Autowired AnswerAttemptsHistoryRepository attempts;
	@Autowired QuestionLogRepository questions;
	@Autowired FaqLogRepository faqLogs;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean FaqVectorService vector;
	@MockitoBean LlmClient llm;
	@Value("${CHAT_MAX_ATTEMPTS}") int maxAttempts;

	User user;
	String authorization;
	Long faqId;

	@BeforeEach void setup() {
		faqLogs.deleteAllInBatch();
		questions.deleteAllInBatch();
		attempts.deleteAllInBatch();
		user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("채팅 테스트").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		authorization = "Bearer " + jwt.createAccessToken(user);
		Long category = jdbc.queryForObject(
				"insert into faq_category(name) values (?) returning id", Long.class, UUID.randomUUID().toString());
		faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
				Long.class, category, "유심 재발급", "매장 방문");
	}

	@Test void successfulNewQuestionReturnsFinalJsonAfterRealJpaWrites() throws Exception {
		when(vector.getSimilarList("유심 재발급", 3)).thenReturn(List.of(
				new FaqSearchResponseDto(faqId, "재발급 방법", "매장 방문", 0.9)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("모의 LLM이 생성한 답변"));

		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"유심 재발급\",\"userId\":999}"));
		assertThat(body).contains("모의 LLM이 생성한 답변", "\"status\":\"SUCCESS\"", "\"success\":true", "\"attemptCount\":1")
				.doesNotContain("event:", "data:");
		var stored = questions.findAll().getFirst();
		assertThat(stored.getUserId()).isEqualTo(user.getId());
		assertThat(stored.getAnswer()).isEqualTo("모의 LLM이 생성한 답변");
		assertThat(faqLogs.findAll()).hasSize(1).first().satisfies(
				log -> assertThat(log.getQuestionLogId()).isEqualTo(stored.getId()));
		assertThat(attempts.findAll()).hasSize(1).first().satisfies(
				attempt -> assertThat(attempt.getStatus()).isEqualTo("SUCCESS"));
		var request = ArgumentCaptor.forClass(LlmRequestDto.class);
		verify(llm).generateAnswer(request.capture());
		assertThat(request.getValue().messages().getLast().content())
				.contains("유심 재발급", "[FAQ ID: " + faqId + "]", "매장 방문");
	}

	@Test void configuredAttemptLimitAllowsMoreThanThreeAttemptsAndBlocksNext() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9)));
		when(llm.generateAnswer(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
		String body = complete(post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}"));
		assertThat(body).contains("\"status\":\"FAIL\"", "\"success\":true", "\"retryable\":true");
		String key = keyFrom(body);
		for (int count = 2; count <= maxAttempts; count++) {
			body = complete(post("/chat/questions/retries").header("Idempotency-Key", key));
			assertThat(body).contains("\"attemptCount\":" + count, key);
		}
		assertThat(body).contains("\"retryable\":false");
		mvc.perform(post("/chat/questions/retries").header("Authorization", authorization)
				.header("Idempotency-Key", key)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.LIMIT_REACHED.getCode()));
		assertThat(attempts.findAll()).hasSize(maxAttempts).allMatch(
				a -> a.getIdempotencyKey().equals(key) && "FAIL".equals(a.getStatus()));
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		verify(llm, times(maxAttempts)).generateAnswer(any());
	}

	@Test void insufficientEvidenceSkipsLlmAndStoresFailure() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.74)));
		assertThat(complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"))).contains("\"status\":\"FAIL\"", "정확한 답변을 찾지 못했습니다.");
		assertThat(attempts.findAll().getFirst().getStatus()).isEqualTo("FAIL");
		verifyNoInteractions(llm);
	}

	@Test void failedFaqInsertRollsBackSuccessLogsButCommitsFailureRecordSeparately() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(-999L, "q", "a", 0.9)));
		when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("저장되지 않아야 하는 답변"));
		String body = complete(post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}"));
		assertThat(body).contains("\"status\":\"FAIL\"", "\"success\":true")
				.doesNotContain("\"status\":\"SUCCESS\"", "foreign key");
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		assertThat(attempts.findAll().getFirst().getStatus()).isEqualTo("FAIL");
	}

	@Test void unauthenticatedRequestsAreRejectedByExistingSecurityFilter() throws Exception {
		mvc.perform(post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("AUTH-001"));
		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", "b".repeat(64)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("AUTH-001"));
		assertThat(attempts.count()).isZero();
		verifyNoInteractions(vector, llm);
	}

	@Test void unknownRetryKeyIsRejectedWithoutStartingGeneration() throws Exception {
		mvc.perform(post("/chat/questions/retries").header("Authorization", authorization)
				.header("Idempotency-Key", "b".repeat(64)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
		assertThat(attempts.count()).isZero();
		verifyNoInteractions(vector, llm);
	}

	@Test void anotherUsersRetryKeyIsRejectedWithoutStartingGeneration() throws Exception {
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9)));
		when(llm.generateAnswer(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
		String body = complete(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}"));
		assertThat(body).contains("\"retryable\":true");
		String key = keyFrom(body);
		User otherUser = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("다른 사용자").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		String otherAuthorization = "Bearer " + jwt.createAccessToken(otherUser);

		mvc.perform(post("/chat/questions/retries").header("Authorization", otherAuthorization)
				.header("Idempotency-Key", key))
				.andExpect(status().isNotFound())
				.andExpect(request().asyncNotStarted())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
		assertThat(attempts.findAll()).hasSize(1).first().satisfies(attempt -> {
			assertThat(attempt.getUserId()).isEqualTo(user.getId());
			assertThat(attempt.getIdempotencyKey()).isEqualTo(key);
			assertThat(attempt.getAttemptCount()).isEqualTo(1);
			assertThat(attempt.getStatus()).isEqualTo("FAIL");
		});
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();
		verify(vector, times(1)).getSimilarList("질문", 3);
		verify(llm, times(1)).generateAnswer(any());
	}

	@Test void concurrentRetryCreatesOnlyOneNewAttemptUnderJpaLock() throws Exception {
		String key = "c".repeat(64);
		var first = new AnswerAttemptsHistory(user.getId(), "질문", 1, key, LocalDateTime.now(), "", "");
		first.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
		attempts.saveAndFlush(first);
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9)));
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch generated = new CountDownLatch(1);
		when(llm.generateAnswer(any())).thenAnswer(call -> {
			if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
			generated.countDown();
			return new LlmResponseDto("답변");
		});
		var barrier = new CyclicBarrier(2);
		try (var callers = Executors.newFixedThreadPool(2)) {
			Callable<String> retry = () -> {
				barrier.await(5, TimeUnit.SECONDS);
				try { service.retryChat(user.getId(), key); return "ACCEPTED"; }
				catch (ChatException exception) { return exception.getErrorCode().getCode(); }
			};
			var a = callers.submit(retry);
			var b = callers.submit(retry);
			assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder("ACCEPTED", ChatErrorCode.PROCESSING.getCode());
			assertThat(attempts.count()).isEqualTo(2);
		} finally {
			release.countDown();
		}
		assertThat(generated.await(5, TimeUnit.SECONDS)).isTrue();
		// 다음 테스트가 시작하기 전에 worker의 트랜잭션까지 끝났는지 제한 시간 안에 확인합니다.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			var latest = attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(user.getId(), key).orElseThrow();
			if ("SUCCESS".equals(latest.getStatus())) return;
			Thread.sleep(20);
		}
		fail("Worker did not persist success");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void timeoutCommitsFailureBeforeReturningError(boolean retry) throws Exception {
		String key = "d".repeat(64);
		AnswerAttemptsHistory initialAttempt = null;
		if (retry) {
			initialAttempt = new AnswerAttemptsHistory(user.getId(), "질문", 1, key, LocalDateTime.now(), "", "");
			initialAttempt.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
			initialAttempt = attempts.saveAndFlush(initialAttempt);
		}
		when(vector.getSimilarList("질문", 3)).thenReturn(List.of(new FaqSearchResponseDto(faqId, "q", "a", 0.9)));
		var entered = new CountDownLatch(1);
		var interrupted = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var worker = new AtomicReference<Thread>();
		when(llm.generateAnswer(any())).thenAnswer(call -> {
			worker.set(Thread.currentThread());
			entered.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
			} catch (InterruptedException exception) {
				interrupted.countDown();
				if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
			}
			return new LlmResponseDto("취소 이후 도착한 답변");
		});
		String timeoutResponseKey;
		var request = retry ? post("/chat/questions/retries").header("Idempotency-Key", key)
				: post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}");
		try {
			var pending = mvc.perform(request.header("Authorization", authorization))
					.andExpect(request().asyncStarted()).andReturn();
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			var timedAttempt = attempts.findAll().stream().filter(a -> "PENDING".equals(a.getStatus())).findFirst().orElseThrow();
			var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
			for (var listener : context.getListeners()) {
				listener.onTimeout(new AsyncEvent(context));
			}

			assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(pending.getAsyncResult()).isInstanceOfSatisfying(ChatException.class,
					exception -> assertThat(exception.getErrorCode()).isEqualTo(ChatErrorCode.RESPONSE_TIMEOUT));
			var stored = attempts.findById(timedAttempt.getId()).orElseThrow();
			assertThat(stored.getStatus()).isEqualTo("FAIL");
			assertThat(stored.getErrorCode()).isEqualTo(ChatErrorCode.RESPONSE_TIMEOUT);
			assertThat(stored.getErrorMessage()).isEqualTo(ChatErrorCode.RESPONSE_TIMEOUT.getMessage());
			assertThat(jdbc.queryForObject("select error_code from answer_attempts_history where attempt_id = ?",
					String.class, stored.getId())).isEqualTo("CHAT-016");
			if (retry) {
				var initial = attempts.findById(initialAttempt.getId()).orElseThrow();
				assertThat(initial.getErrorCode()).isEqualTo(ChatErrorCode.VECTOR_SEARCH_FAILED);
				assertThat(stored.getAttemptCount()).isEqualTo(2);
			}
			String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.code").value("CHAT-016"))
					.andExpect(jsonPath("$.message").value("답변 생성 시간이 초과되었습니다."))
					.andExpect(jsonPath("$.data.status").value("FAIL"))
					.andExpect(jsonPath("$.data.idempotencyKey").value(stored.getIdempotencyKey()))
					.andExpect(jsonPath("$.data.attemptCount").value(retry ? 2 : 1))
					.andExpect(jsonPath("$.data.retryable").value(true))
					.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
			timeoutResponseKey = keyFrom(body);
		} finally {
			release.countDown();
			if (worker.get() != null) {
				worker.get().join(5_000L);
				assertThat(worker.get().isAlive()).isFalse();
			}
		}
		assertThat(questions.count()).isZero();
		assertThat(faqLogs.count()).isZero();

		doReturn(new LlmResponseDto("재시도 답변")).when(llm).generateAnswer(any());
		String retried = complete(post("/chat/questions/retries").header("Idempotency-Key", timeoutResponseKey));
		assertThat(keyFrom(retried)).isEqualTo(timeoutResponseKey);
		assertThat(retried).contains("\"status\":\"SUCCESS\"", "\"attemptCount\":" + (retry ? 3 : 2));
		var timedOut = attempts.findAll().stream()
				.filter(attempt -> attempt.getAttemptCount() == (retry ? 2 : 1)).findFirst().orElseThrow();
		assertThat(timedOut.getStatus()).isEqualTo("FAIL");
		assertThat(timedOut.getErrorCode()).isEqualTo(ChatErrorCode.RESPONSE_TIMEOUT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"SUCCESS", "FAIL"})
	void timeoutStorageLeavesFinishedAttemptsUnchanged(String status) {
		var attempt = new AnswerAttemptsHistory(user.getId(), "질문", 1, "e".repeat(64), LocalDateTime.now(), "", "");
		if ("SUCCESS".equals(status)) {
			attempt.succeed();
		} else {
			attempt.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
		}
		attempt = attempts.saveAndFlush(attempt);

		attemptsService.saveAnswerTimeout(attempt);

		var stored = attempts.findById(attempt.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(status);
		assertThat(stored.getErrorCode()).isEqualTo(attempt.getErrorCode());
		assertThat(stored.getErrorMessage()).isEqualTo(attempt.getErrorMessage());
	}

	private String complete(MockHttpServletRequestBuilder request) throws Exception {
		var pending = mvc.perform(request.header("Authorization", authorization))
				.andExpect(request().asyncStarted()).andReturn();
		pending.getAsyncResult(10_000);
		return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.data.success").doesNotExist())
				.andExpect(jsonPath("$.data.answer").isString()).andReturn()
				.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private String keyFrom(String body) {
		var matcher = Pattern.compile("\"idempotencyKey\":\"([0-9a-f]{64})\"").matcher(body);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
