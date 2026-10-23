package com.ubot.counsel;

import com.ubot.counsel.message.dto.response.MessageReceiptResponseDto;

import com.sun.net.httpserver.HttpServer;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.session.entity.Session;
import com.ubot.counsel.session.repository.SessionRepository;
import com.ubot.counsel.session.service.SessionService;
import com.ubot.counsel.session.controller.SessionController;
import com.ubot.counsel.message.entity.Message;
import com.ubot.counsel.message.repository.MessageRepository;
import com.ubot.counsel.message.service.MessageService;
import com.ubot.counsel.message.controller.MessageController;
import com.ubot.counsel.safety.model.GuardDecision;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.repository.SafetyRepository;
import com.ubot.counsel.safety.service.SafetyGuardService;
import com.ubot.counsel.safety.service.SafetyProfileService;
import com.ubot.counsel.admin.service.AdminCounselService;
import com.ubot.counsel.admin.service.AdminSafetySentenceService;
import com.ubot.counsel.admin.dto.request.SafetySentenceCreateRequestDto;
import com.ubot.counsel.admin.dto.request.SafetySentenceUpdateRequestDto;
import com.ubot.counsel.session.dto.request.HandoffRequestDto;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.context.annotation.Import({com.ubot.PgvectorTestConfiguration.class, com.ubot.RedisTestConfiguration.class})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class CounselIntegrationTest {
	static final ObjectMapper JSON = new ObjectMapper();
	static final AtomicInteger EMBEDDING_CALLS = new AtomicInteger();
	// 모델 정확도 대신 높은/낮은 유사도에 따른 전달·저장을 검증하는 고정 응답임
	static final Set<String> HIGH_SIMILARITY_INPUTS = Set.of(
			"당신은 정말 무능한 상담사네요.", "당신은 바보예요.");
	static final UUID OWN_CONVERSATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
	static volatile CountDownLatch entered = new CountDownLatch(0);
	static volatile CountDownLatch release = new CountDownLatch(0);
	static final HttpServer UPSTREAM;
	static {
		try {
			UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			UPSTREAM.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			UPSTREAM.createContext("/v1/embeddings", exchange -> {
				EMBEDDING_CALLS.incrementAndGet();
				var payload = JSON.readTree(exchange.getRequestBody().readAllBytes()).path("input");
				var input = payload.isArray() ? payload.get(0).asText() : payload.asText();
				if (input.contains("느린")) {
					entered.countDown();
					try { release.await(10, TimeUnit.SECONDS); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				}
				int status = input.contains("GPU장애") ? 503 : 200;
				List<Double> vector = new ArrayList<>(Collections.nCopies(input.contains("차원오류") ? 3 : 1024, 0.0));
				if (!input.contains("영벡터")) vector.set(HIGH_SIMILARITY_INPUTS.contains(input) ? 0 : 1, 1.0);
				byte[] bytes = JSON.writeValueAsBytes(Map.of("data", List.of(Map.of("embedding", vector, "index", 0))));
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
			});
			UPSTREAM.start();
		} catch (Exception e) { throw new ExceptionInInitializerError(e); }
	}
	@DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
		r.add("counsel.guard.transport", () -> "http");
		r.add("counsel.guard.embedding-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1/embeddings");
		r.add("counsel.guard.response-timeout-ms", () -> 1000);
		r.add("counsel.maintenance-delay-ms", () -> 3600000);
		r.add("app.cors.allowed-origins", () -> "https://ureca-ubot.github.io");
	}
	@Autowired com.ubot.auth.util.JwtUtil jwt;
	@Autowired com.ubot.user.repository.UserRepository users;
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.ubot.auth.service.AccessTokenRevocationService revocations;
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.ubot.auth.service.LoginAttemptService loginAttempts;
	// 정리 로직은 각 테스트에서 직접 호출함. 시작 직후의 스케줄 실행과 TRUNCATE가 경쟁하지 않게 함
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.ubot.counsel.common.config.CounselMaintenanceScheduler maintenanceScheduler;
	String token(String name) {
		long id = switch(name) { case "admin" -> 99; case "counselor" -> 10; case "stranger" -> 2; default -> 1; };
		var user = users.findById(id).orElseThrow();
		var issuer = name.equals("expired") ? new com.ubot.auth.util.JwtUtil(
				"MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=", -1000L) : jwt;
		return "Bearer " + issuer.createAccessToken(user);
	}
	@Autowired JdbcTemplate jdbc;
	@Autowired SessionService sessions;
	@Autowired MessageService messages;
	@Autowired SafetyGuardService guard;
	@Autowired ForbiddenWordFilterService forbiddenWordFilter;
	@Autowired AdminCounselService admin;
	@Autowired MockMvc mvc;
	@Autowired SafetyProfileService seeder;
	@Autowired com.ubot.counsel.common.config.CounselProperties counselProperties;
	@Autowired com.ubot.counsel.safety.repository.SafetySentenceRepository sentenceRepository;
	@Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;
	@Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
	@Autowired AdminSafetySentenceService safetySentences;
	@Autowired com.ubot.counsel.history.service.ConversationTimelineService timeline;
	@Autowired com.ubot.conversation.service.ConversationFaqSelectionService faqSelections;
	final Caller customer = new Caller(1, "USER");
	final Caller counselor = new Caller(10, "COUNSELOR");
	final Caller administrator = new Caller(99, "ADMIN");

	@BeforeEach void reset() {
		release.countDown();
		entered = new CountDownLatch(0);
		release = new CountDownLatch(0);
		jdbc.execute("""
				TRUNCATE counseling.sessions, counseling.counselor_presence, counseling.guard_profiles,
				counseling.rate_buckets, counseling.role_audit RESTART IDENTITY CASCADE
				""");
		jdbc.execute("TRUNCATE public.users RESTART IDENTITY CASCADE");
		jdbc.execute("TRUNCATE public.forbidden_words RESTART IDENTITY CASCADE");
		forbiddenWordFilter.refreshForbiddenWordCache();
		jdbc.update("""
				INSERT INTO public.users(user_id,email,password_hash,name,role)
				SELECT id,concat('counsel-',id,'@example.test'),'test-hash','test',role
				FROM (VALUES (1,'USER'),(2,'USER'),(10,'COUNSELOR'),(11,'COUNSELOR'),(99,'ADMIN')) v(id,role)
				""");
		long conversation = jdbc.queryForObject(
				"INSERT INTO public.conversations(public_id,user_id,type) VALUES (?,1,'MEMBER') RETURNING conversation_id",
				Long.class, OWN_CONVERSATION);
		jdbc.update("""
				INSERT INTO public.question_log(user_id,conversation_id,user_question,llm_question)
				VALUES (1,?,'이전 요금 문의','이전 AI 답변'),(1,?,'이전 추가 문의','추가 AI 답변')
				""", conversation, conversation);
		jdbc.update("""
				INSERT INTO counseling.guard_profiles(profile_version,model_name,preprocessing_version,dataset_sha256)
				VALUES ('insult-v1','ubot-embedding','nfkc-space-v1','test-only')
				""");
		String vector = "[" + String.join(",", Collections.nCopies(1024, "0")).replaceFirst("0", "1") + "]";
		jdbc.update("""
				INSERT INTO counseling.safety_filter_vectors(category,example_text,embedding,severity,model_name,profile_version)
				VALUES ('INSULT','fixture',CAST(? AS public.vector),2,'ubot-embedding','insult-v1')
				""", vector);
		EMBEDDING_CALLS.set(0);
	}
	@AfterAll static void stop() { UPSTREAM.stop(0); }
	Session active() {
		sessions.updateAvailability(counselor, "AVAILABLE");
		return sessions.createSession(customer);
	}
	@Test void counselEndpointsUseExistingAuthenticationAndHideInternalIds() throws Exception {
		mvc.perform(post("/counsel/requests")).andExpect(status().isUnauthorized());
		// FE의 토큰 재발급이 만료 코드에 의존하므로 상담 경로에서도 JWT-007을 확인함
		mvc.perform(get("/counsel/sessions/current").header("Authorization", token("expired")))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("JWT-007"));
		mvc.perform(post("/counsel/requests").header("Authorization", token("customer")))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.data.customerId").doesNotExist()).andExpect(jsonPath("$.data.counselorId").doesNotExist());
	}
	@Test void rateLimitResetsAfterConfiguredWindow() {
		Session room = active();
		jdbc.update("INSERT INTO counseling.rate_buckets(user_id,requests,window_start) VALUES (1,30,now()-interval '61 seconds')");
		assertThat(messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "요금 문의").status()).isEqualTo("ALLOWED");
		assertThat(jdbc.queryForObject("SELECT requests FROM counseling.rate_buckets WHERE user_id=1", Integer.class)).isEqualTo(1);
	}
	@Test void onlyAllowedIsDeliveredAndOriginalIsPreserved() {
		Session room = active();
		String original = "  요금이 계속 잘못 나와서 정말 화가 납니다.  ";
		var allowed = messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), original);
		var blocked = messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "당신은 정말 무능한 상담사네요.");
		var failed = messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "GPU장애 테스트");
		assertThat(allowed.status()).isEqualTo("ALLOWED");
		assertThat(blocked.status()).isEqualTo("BLOCKED");
		assertThat(failed.status()).isEqualTo("CHECK_FAILED");
		assertThat(blocked.deliverySequence()).isNull();
		assertThat(failed.deliverySequence()).isNull();
		assertThat(messages.getMessages(counselor, room.sessionId(), 0, 50)).singleElement()
				.satisfies(m -> { assertThat(m.content()).isEqualTo(original); assertThat(m.deliverySequence()).isEqualTo(1); });
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_events", Integer.class)).isEqualTo(3);
	}
	@Test void simultaneousDuplicatesUseOneMessageAndOneEmbedding() throws Exception {
		Session room = active();
		UUID clientId = UUID.randomUUID();
		entered = new CountDownLatch(1); release = new CountDownLatch(1);
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<MessageReceiptResponseDto> first = pool.submit(() -> messages.sendMessage(customer, room.sessionId(), clientId, "느린 문의"));
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			assertThat(messages.getMessages(counselor, room.sessionId(), 0, 50)).isEmpty();
			var duplicate = messages.sendMessage(customer, room.sessionId(), clientId, "느린 문의");
			assertThat(duplicate.status()).isEqualTo("PENDING");
			release.countDown();
			var completed = first.get(5, TimeUnit.SECONDS);
			assertThat(messages.sendMessage(customer, room.sessionId(), clientId, "느린 문의")).isEqualTo(completed);
			assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages", Integer.class)).isEqualTo(1);
			assertThatThrownBy(() -> messages.sendMessage(customer, room.sessionId(), clientId, "바꾼 내용")).isInstanceOf(CounselException.class);
		} finally { release.countDown(); }
	}
	@Test void lateEarlierMessageGetsLaterDeliverySequence() throws Exception {
		Session room = active();
		entered = new CountDownLatch(1); release = new CountDownLatch(1);
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			var slow = pool.submit(() -> messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "느린 A 문의"));
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			var fast = messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "빠른 B 문의");
			assertThat(fast.deliverySequence()).isEqualTo(1);
			release.countDown();
			var late = slow.get(5, TimeUnit.SECONDS);
			assertThat(late.messageId()).isLessThan(fast.messageId());
			assertThat(late.deliverySequence()).isEqualTo(2);
			assertThat(messages.getMessages(counselor, room.sessionId(), 1, 50)).singleElement()
					.satisfies(m -> assertThat(m.content()).isEqualTo("느린 A 문의"));
		} finally { release.countDown(); }
	}
	@Test void simultaneousRequestsCannotDoubleAssign() throws Exception {
		sessions.updateAvailability(counselor, "AVAILABLE");
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Session>> requests = new ArrayList<>();
			for (int i = 0; i < 12; i++) requests.add(pool.submit(() -> sessions.createSession(customer)));
			Set<UUID> ids = new HashSet<>();
			for (var result : requests) ids.add(result.get(10, TimeUnit.SECONDS).sessionId());
			assertThat(ids).hasSize(1);
			Session second = sessions.createSession(new Caller(2, "USER"));
			assertThat(second.status()).isEqualTo("WAITING");
			sessions.updateAvailability(new Caller(11, "COUNSELOR"), "AVAILABLE");
			assertThat(sessions.getSession(new Caller(2, "USER"), second.sessionId()).counselorId()).isEqualTo(11L);
		}
	}
	@Test void fifoAndEndReleaseTheCounselor() {
		Session first = sessions.createSession(customer);
		Session second = sessions.createSession(new Caller(2, "USER"));
		sessions.updateAvailability(counselor, "AVAILABLE");
		assertThat(sessions.getSession(customer, first.sessionId()).status()).isEqualTo("ACTIVE");
		assertThat(sessions.getSession(new Caller(2, "USER"), second.sessionId()).status()).isEqualTo("WAITING");
		sessions.endSession(customer, first.sessionId());
		assertThat(sessions.getSession(new Caller(2, "USER"), second.sessionId()).counselorId()).isEqualTo(10L);
	}
	@Test void heartbeatGraceAndReassignmentRevokeOldCounselor() {
		Session room = active();
		jdbc.update("UPDATE counseling.counselor_presence SET last_heartbeat_at=now()-interval '50 seconds' WHERE user_id=10");
		sessions.maintainSessions();
		assertThat(sessions.getSession(customer, room.sessionId()).status()).isEqualTo("PAUSED");
		sessions.updateHeartbeat(counselor);
		assertThat(sessions.getSession(customer, room.sessionId()).status()).isEqualTo("ACTIVE");
		jdbc.update("UPDATE counseling.counselor_presence SET last_heartbeat_at=now()-interval '200 seconds' WHERE user_id=10");
		sessions.updateAvailability(new Caller(11, "COUNSELOR"), "AVAILABLE");
		assertThat(sessions.getSession(customer, room.sessionId()).counselorId()).isEqualTo(11L);
		assertThatThrownBy(() -> messages.getMessages(counselor, room.sessionId(), 0, 50)).isInstanceOf(CounselException.class);
		sessions.updateHeartbeat(counselor);
		assertThat(sessions.getSession(customer, room.sessionId()).counselorId()).isEqualTo(11L);
	}
	@Test void messageCheckingDuringReassignmentIsNeverDelivered() throws Exception {
		Session room = active();
		entered = new CountDownLatch(1); release = new CountDownLatch(1);
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			var slow = pool.submit(() -> messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "느린 개인정보 문의"));
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			jdbc.update("UPDATE counseling.counselor_presence SET last_heartbeat_at=now()-interval '200 seconds' WHERE user_id=10");
			sessions.updateAvailability(new Caller(11, "COUNSELOR"), "AVAILABLE");
			release.countDown();
			assertThat(slow.get(5, TimeUnit.SECONDS).status()).isEqualTo("CHECK_FAILED");
			assertThat(messages.getMessages(new Caller(11, "COUNSELOR"), room.sessionId(), 0, 50)).isEmpty();
		} finally { release.countDown(); }
	}
	@Test void timeoutMalformedVectorAndMissingProfileFailClosed() {
		assertThat(guard.checkMessage("GPU장애").status()).isEqualTo("CHECK_FAILED");
		assertThat(guard.checkMessage("차원오류").errorCode()).isEqualTo("EMBEDDING_DIMENSION");
		assertThat(guard.checkMessage("영벡터").errorCode()).isEqualTo("EMBEDDING_ZERO");
		entered = new CountDownLatch(1); release = new CountDownLatch(1);
		try { assertThat(guard.checkMessage("느린 요청").errorCode()).isEqualTo("EMBEDDING_TIMEOUT"); }
		finally { release.countDown(); }
		jdbc.update("UPDATE counseling.safety_filter_vectors SET active=false");
		assertThat(guard.checkMessage("정상 문의").errorCode()).isEqualTo("PROFILE_NOT_READY");
	}
	@Test void existingForbiddenWordApiControlsCounselBeforeEmbedding() throws Exception {
		String adminToken = token("admin");
		String created = mvc.perform(post("/admin/forbidden-words").header("Authorization", adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"필터검증\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long wordId = JSON.readTree(created).path("data").path("id").asLong();
		Session room = active();
		UUID clientId = UUID.randomUUID();
		String messagePath = "/counsel/sessions/" + room.sessionId() + "/messages";
		String body = JSON.writeValueAsString(Map.of("clientMessageId", clientId, "content", "필터검증을 인용한 문의"));
		for (int retry = 0; retry < 2; retry++) {
			mvc.perform(post(messagePath).header("Authorization", token("customer"))
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("BLOCKED"))
					.andExpect(jsonPath("$.data.deliverySequence").isEmpty());
		}
		assertThat(messages.sendMessage(counselor, room.sessionId(), UUID.randomUUID(), "필터검증 답변").status())
				.isEqualTo("BLOCKED");
		mvc.perform(post("/admin/counsel/safety/evaluate").header("Authorization", adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"필터검증\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("BLOCKED"));
		assertThat(EMBEDDING_CALLS.get()).isZero();
		assertThat(messages.getMessages(customer, room.sessionId(), 0, 100)).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM counseling.safety_filter_events
				WHERE decision='BLOCKED' AND matched_vector_id IS NULL AND score IS NULL
				  AND rule_version='forbidden-word-semantic-v2'
				""", Integer.class)).isEqualTo(2);

		mvc.perform(patch("/admin/forbidden-words/" + wordId).header("Authorization", adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"변경금지어\"}")).andExpect(status().isOk());
		assertThat(messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "필터검증을 인용한 문의").status())
				.isEqualTo("ALLOWED");
		assertThat(messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "변경금지어 문의").status())
				.isEqualTo("BLOCKED");
		assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
		mvc.perform(patch("/admin/forbidden-words/" + wordId + "/status").header("Authorization", adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
		assertThat(messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "변경금지어 문의").status())
				.isEqualTo("ALLOWED");
		mvc.perform(patch("/admin/forbidden-words/" + wordId + "/status").header("Authorization", adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
		assertThat(guard.checkMessage("변경금지어 문의").status()).isEqualTo("BLOCKED");
		assertThat(EMBEDDING_CALLS.get()).isEqualTo(2);
	}
	@Test void restartRecoversStrandedPendingWithoutPublishing() {
		Session room = active();
		jdbc.update("""
				INSERT INTO counseling.messages(session_id,sender_id,sender_role,assignment_version,client_message_id,content,status,created_at)
				VALUES (?,1,'USER',?,?,?,'PENDING',now()-interval '1 minute')
				""", room.sessionId(), room.assignmentVersion(), UUID.randomUUID(), "stranded");
		messages.recoverPending();
		assertThat(jdbc.queryForObject("SELECT status FROM counseling.messages", String.class)).isEqualTo("CHECK_FAILED");
		assertThat(jdbc.queryForObject("SELECT error_code FROM counseling.safety_filter_events", String.class)).isEqualTo("PENDING_EXPIRED");
		assertThat(messages.getMessages(counselor, room.sessionId(), 0, 50)).isEmpty();
	}
	@Test void databaseFailureRollsBackSequenceAndDecision() {
		Session room = active();
		jdbc.execute("ALTER TABLE counseling.safety_filter_events ADD CONSTRAINT reject_test CHECK (decision <> 'ALLOWED')");
		try {
			assertThatThrownBy(() -> messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "정상 문의"))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT last_delivered_seq FROM counseling.sessions WHERE id=?", Long.class, room.sessionId())).isZero();
			assertThat(jdbc.queryForObject("SELECT status FROM counseling.messages", String.class)).isEqualTo("PENDING");
			assertThat(messages.getMessages(counselor, room.sessionId(), 0, 50)).isEmpty();
		} finally { jdbc.execute("ALTER TABLE counseling.safety_filter_events DROP CONSTRAINT reject_test"); }
	}
	@Test void ownershipRoleAndInputChecksProtectAllEndpoints() throws Exception {
		Session room = active();
		mvc.perform(get("/counsel/sessions/" + room.sessionId() + "/messages").header("Authorization", token("stranger"))).andExpect(status().isForbidden());
		mvc.perform(get("/admin/counsel/safety/events").header("Authorization", token("customer"))).andExpect(status().isForbidden());
		mvc.perform(get("/admin/counsel/safety/summary").header("Authorization", token("counselor"))).andExpect(status().isForbidden());
		mvc.perform(post("/counsel/counselors/availability").header("Authorization", token("customer"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/counsel/sessions/" + room.sessionId() + "/messages").header("Authorization", token("customer"))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "content", "x".repeat(1001)))))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/counsel/sessions/" + room.sessionId() + "/messages?afterSequence=-1").header("Authorization", token("customer"))).andExpect(status().isBadRequest());
		mvc.perform(get("/counsel/sessions/" + room.sessionId() + "/messages?limit=101").header("Authorization", token("customer"))).andExpect(status().isBadRequest());
	}
	@Test void spoofedSenderIsIgnoredAndAdminNeverSeesRawContent() throws Exception {
		Session room = active();
		mvc.perform(post("/counsel/sessions/" + room.sessionId() + "/messages").header("Authorization", token("customer"))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of(
						"clientMessageId", UUID.randomUUID(), "content", "당신은 정말 무능한 상담사네요.", "senderId", 10, "role", "ADMIN"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("BLOCKED"));
		assertThat(jdbc.queryForObject("SELECT sender_id FROM counseling.messages", Long.class)).isEqualTo(1L);
		String result = mvc.perform(get("/admin/counsel/safety/events?decision=BLOCKED").header("Authorization", token("admin")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].senderRole").value("USER"))
				.andReturn().getResponse().getContentAsString();
		assertThat(result).doesNotContain("무능한", "example_text", "\"content\"");
		mvc.perform(get("/admin/counsel/safety/summary").header("Authorization", token("admin")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.blocked").value(1));
	}
	@Test void rateLimitIsSharedAndDuplicateRetriesDoNotConsumeIt() {
		Session room = active();
		UUID client = UUID.randomUUID();
		messages.sendMessage(customer, room.sessionId(), client, "당신은 바보예요.");
		for (int i = 0; i < 40; i++) messages.sendMessage(customer, room.sessionId(), client, "당신은 바보예요.");
		for (int i = 0; i < 29; i++) messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "당신은 바보예요.");
		assertThatThrownBy(() -> messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "다음 메시지"))
				.isInstanceOfSatisfying(CounselException.class, e -> assertThat(e.getErrorCode().getStatus().value()).isEqualTo(429));
	}
	@Test void promotionRequiresAdminAndIdleUserAndIsAudited() {
		assertThatThrownBy(() -> admin.updateCounselorRole(customer, 2, true)).isInstanceOf(CounselException.class);
		sessions.createSession(customer);
		assertThatThrownBy(() -> admin.updateCounselorRole(administrator, 1, true)).isInstanceOf(CounselException.class);
		admin.updateCounselorRole(administrator, 2, true);
		assertThat(jdbc.queryForObject("SELECT role FROM public.users WHERE user_id=2", String.class)).isEqualTo("COUNSELOR");
		admin.updateCounselorRole(administrator, 2, false);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.role_audit", Integer.class)).isEqualTo(2);
	}
	@Test void seedingIsAtomicImmutableAndIdempotent() throws Exception {
		jdbc.execute("TRUNCATE counseling.guard_profiles CASCADE");
		seeder.seedProfile();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors", Integer.class)).isEqualTo(50);
		assertThat(safetySentences.getSentences(administrator, "", 0).items()).hasSize(50)
				.allSatisfy(s -> assertThat(s.applied()).isTrue());
		int calls = EMBEDDING_CALLS.get();
		seeder.seedProfile();
		assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
		jdbc.update("UPDATE counseling.guard_profiles SET dataset_sha256='changed'");
		assertThatThrownBy(() -> seeder.seedProfile()).isInstanceOf(IllegalStateException.class);
	}
	@Test void adminRangeRejectsUnboundedScans() {
		assertThatThrownBy(() -> admin.getSafetySummary(administrator, Instant.EPOCH, Instant.now())).isInstanceOf(IllegalArgumentException.class);
	}
	@Test void sentenceApiCreatesIndexesEditsAndDeletesWithHistoricalVectorsPreserved() throws Exception {
		String base = "/admin/counsel/safety/sentences";
		String body = mvc.perform(post(base).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"당신은 사고력이 전혀 없는 사람이네요.\",\"severity\":2}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.indexStatus").value("PENDING"))
				.andExpect(jsonPath("$.data.applied").value(false)).andReturn().getResponse().getContentAsString();
		long id = JSON.readTree(body).path("data").path("sentenceId").asLong();
		assertThat(EMBEDDING_CALLS.get()).isZero();
		body = mvc.perform(post(base + "/" + id + "/index").header("Authorization", token("admin"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.indexStatus").value("INDEXED"))
				.andExpect(jsonPath("$.data.applied").value(true)).andReturn().getResponse().getContentAsString();
		long oldVector = JSON.readTree(body).path("data").path("vectorId").asLong();
		// 이미 성공한 색인 요청을 반복해도 GPU 작업을 더 쓰지 않아야 함
		safetySentences.indexSentence(administrator, id, 1);
		assertThat(EMBEDDING_CALLS.get()).isEqualTo(1);
		jdbc.update("UPDATE counseling.safety_filter_vectors SET active=false WHERE id<>?", oldVector);
		var room = active();
		messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "안녕하세요 요금 문의입니다.");
		assertThat(jdbc.queryForObject("SELECT matched_vector_id FROM counseling.safety_filter_events", Long.class)).isEqualTo(oldVector);
		mvc.perform(patch(base + "/" + id).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":1,\"content\":\"변경한 관리 문장\",\"severity\":3,\"enabled\":true}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2))
				.andExpect(jsonPath("$.data.applied").value(false));
		assertThat(jdbc.queryForObject("SELECT active FROM counseling.safety_filter_vectors WHERE id=?", Boolean.class, oldVector)).isFalse();
		assertThat(guard.checkMessage("요금 문의").errorCode()).isEqualTo("PROFILE_NOT_READY");
		mvc.perform(patch(base + "/" + id).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":1,\"content\":\"오래된 수정\",\"severity\":2,\"enabled\":true}"))
				.andExpect(status().isConflict());
		var indexed = safetySentences.indexSentence(administrator, id, 2);
		assertThat(indexed.vectorId()).isNotEqualTo(oldVector);
		assertThat(guard.checkMessage("요금 문의").vectorId()).isEqualTo(indexed.vectorId());
		mvc.perform(delete(base + "/" + id + "?revision=2").header("Authorization", token("admin"))).andExpect(status().isOk());
		mvc.perform(get(base + "/" + id).header("Authorization", token("admin"))).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors WHERE active", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT example_text FROM counseling.safety_filter_vectors WHERE id=?", String.class, oldVector))
				.isEqualTo("당신은 사고력이 전혀 없는 사람이네요.");
		assertThat(jdbc.queryForObject("SELECT matched_vector_id FROM counseling.safety_filter_events", Long.class)).isEqualTo(oldVector);
		assertThat(jdbc.queryForList("SELECT action FROM counseling.safety_sentence_audit WHERE sentence_id=? ORDER BY id", String.class, id))
				.containsExactly("CREATE", "INDEX_REQUESTED", "INDEXED", "UPDATE", "INDEX_REQUESTED", "INDEXED", "DELETE");
	}
	@Test void sentenceEndpointsRejectGuestsUsersCounselorsAndInvalidInput() throws Exception {
		String base = "/admin/counsel/safety/sentences";
		for (String token : List.of("", token("customer"), token("counselor"))) {
			for (var request : List.of(get(base), get(base + "/1"), post(base), patch(base + "/1"),
					delete(base + "/1?revision=1"), post(base + "/1/index"))) {
				if (!token.isEmpty()) request.header("Authorization", token);
				mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
						.andExpect(status().is(token.isEmpty() ? 401 : 403));
			}
		}
		for (String invalid : List.of("{}", "{\"content\":\" \",\"severity\":2}",
				"{\"content\":\"valid\",\"severity\":0}", "{\"content\":\"valid\",\"severity\":4}",
				JSON.writeValueAsString(Map.of("content", "x".repeat(1001), "severity", 2)))) {
			mvc.perform(post(base).header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content(invalid))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get(base + "?page=-1").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
		mvc.perform(delete(base + "/1").header("Authorization", token("admin"))).andExpect(status().isBadRequest());
		mvc.perform(post(base + "/1/index").header("Authorization", token("admin"))
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(options(base + "/1").header("Origin", "https://ureca-ubot.github.io")
				.header("Access-Control-Request-Method", "PATCH").header("Access-Control-Request-Headers", "authorization,content-type"))
				.andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
		assertThat(EMBEDDING_CALLS.get()).isZero();
	}
	@Test void normalizedDuplicatesAndConcurrentCreatesAreRejectedWithoutOverwriting() throws Exception {
		var one = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("  ＡＢＣ\t관리 문장  ", 2));
		assertThatThrownBy(() -> safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("ABC 관리 문장", 3)))
				.isInstanceOfSatisfying(CounselException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("COUNSEL-014"));
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = new ArrayList<Future<Boolean>>();
			for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> {
				try {
					safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("동시 등록 문장", 2));
					return true;
				} catch (CounselException e) {
					assertThat(e.getErrorCode().getCode()).isEqualTo("COUNSEL-014"); return false;
				}
			}));
			int accepted = 0;
			for (var task : tasks) if (task.get(5, TimeUnit.SECONDS)) accepted++;
			assertThat(accepted).isEqualTo(1);
		}
		assertThat(safetySentences.getSentence(administrator, one.sentenceId()).severity()).isEqualTo(2);
		assertThat(safetySentences.getSentences(administrator, "동시", 0).items()).hasSize(1);
		safetySentences.deleteSentence(administrator, one.sentenceId(), 1);
		assertThat(safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("ABC 관리 문장", 2)).sentenceId())
				.isNotEqualTo(one.sentenceId());
	}
	@Test void indexingFailureIsVisibleAndRetryRequiresNoTextChange() throws Exception {
		var sentence = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("느린 색인 문장", 2));
		entered = new CountDownLatch(1); release = new CountDownLatch(1);
		try {
			mvc.perform(post("/admin/counsel/safety/sentences/" + sentence.sentenceId() + "/index")
					.header("Authorization", token("admin")).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
					.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("COUNSEL-015"));
			var failed = safetySentences.getSentence(administrator, sentence.sentenceId());
			assertThat(failed.indexStatus()).isEqualTo("FAILED");
			assertThat(failed.indexError()).isEqualTo("EMBEDDING_TIMEOUT");
			assertThat(failed.vectorId()).isNull();
		} finally { release.countDown(); }
		var retried = safetySentences.indexSentence(administrator, sentence.sentenceId(), 1);
		assertThat(retried.applied()).isTrue();
		assertThat(retried.indexError()).isNull();
	}
	@Test void indexingCannotPublishAfterEditOrDeleteAndDuplicateIndexDoesNotCallGpu() throws Exception {
		for (boolean delete : List.of(false, true)) {
			var sentence = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("느린 색인 " + delete, 2));
			entered = new CountDownLatch(1); release = new CountDownLatch(1);
			try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
				var pending = pool.submit(() -> safetySentences.indexSentence(administrator, sentence.sentenceId(), 1));
				assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
				int calls = EMBEDDING_CALLS.get();
				assertThat(safetySentences.getSentence(administrator, sentence.sentenceId()).indexStatus()).isEqualTo("INDEXING");
				assertThatThrownBy(() -> safetySentences.indexSentence(administrator, sentence.sentenceId(), 1))
						.isInstanceOf(CounselException.class);
				assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
				if (delete) safetySentences.deleteSentence(administrator, sentence.sentenceId(), 1);
				else safetySentences.updateSentence(administrator, sentence.sentenceId(),
						new SafetySentenceUpdateRequestDto(1L, "새 문장", 2, true));
				release.countDown();
				assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CounselException.class);
				assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors", Integer.class)).isEqualTo(1);
			} finally { release.countDown(); }
		}
	}
	@Test void disabledSentencesAndWrongProfileCannotBeIndexed() {
		var sentence = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("관리 문장", 2));
		safetySentences.updateSentence(administrator, sentence.sentenceId(), new SafetySentenceUpdateRequestDto(1L, "관리 문장", 2, false));
		assertThatThrownBy(() -> safetySentences.indexSentence(administrator, sentence.sentenceId(), 2)).isInstanceOf(CounselException.class);
		safetySentences.updateSentence(administrator, sentence.sentenceId(), new SafetySentenceUpdateRequestDto(2L, "관리 문장", 2, true));
		jdbc.update("UPDATE counseling.guard_profiles SET preprocessing_version='other'");
		assertThatThrownBy(() -> safetySentences.indexSentence(administrator, sentence.sentenceId(), 3))
				.isInstanceOfSatisfying(CounselException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("COUNSEL-016"));
		assertThat(EMBEDDING_CALLS.get()).isZero();
	}
	@Test void expiredIndexLeaseCanBeRetriedAndRateLimitPreventsGpuWork() {
		var sentence = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("관리 문장", 2));
		jdbc.update("UPDATE counseling.safety_sentences SET index_token=?,index_started_at=now()-interval '31 seconds' WHERE id=?",
				UUID.randomUUID(), sentence.sentenceId());
		assertThat(safetySentences.getSentence(administrator, sentence.sentenceId()).indexStatus()).isEqualTo("PENDING");
		assertThat(safetySentences.indexSentence(administrator, sentence.sentenceId(), 1).applied()).isTrue();
		var next = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("다음 문장", 2));
		jdbc.update("UPDATE counseling.rate_buckets SET requests=30 WHERE user_id=99");
		int calls = EMBEDDING_CALLS.get();
		assertThatThrownBy(() -> safetySentences.indexSentence(administrator, next.sentenceId(), 1))
				.isInstanceOfSatisfying(CounselException.class, e -> assertThat(e.getErrorCode().getStatus().value()).isEqualTo(429));
		assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
		assertThat(safetySentences.getSentence(administrator, next.sentenceId()).indexStatus()).isEqualTo("PENDING");
	}
	@Test void auditFailureRollsBackSentenceMutationAndIndexPublication() {
		var sentence = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("관리 문장", 2));
		jdbc.execute("ALTER TABLE counseling.safety_sentence_audit ADD CONSTRAINT reject_index_test CHECK (action <> 'INDEXED')");
		try {
			assertThatThrownBy(() -> safetySentences.indexSentence(administrator, sentence.sentenceId(), 1))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(safetySentences.getSentence(administrator, sentence.sentenceId()).vectorId()).isNull();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors", Integer.class)).isEqualTo(1);
		} finally { jdbc.execute("ALTER TABLE counseling.safety_sentence_audit DROP CONSTRAINT reject_index_test"); }
		jdbc.execute("ALTER TABLE counseling.safety_sentence_audit ADD CONSTRAINT reject_update_test CHECK (action <> 'UPDATE')");
		try {
			assertThatThrownBy(() -> safetySentences.updateSentence(administrator, sentence.sentenceId(),
					new SafetySentenceUpdateRequestDto(1L, "변경 문장", 3, false)))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(safetySentences.getSentence(administrator, sentence.sentenceId()).revision()).isEqualTo(1);
			assertThat(safetySentences.getSentence(administrator, sentence.sentenceId()).content()).isEqualTo("관리 문장");
		} finally { jdbc.execute("ALTER TABLE counseling.safety_sentence_audit DROP CONSTRAINT reject_update_test"); }
	}
	long createFaq(String question, String answer) {
		Long category = jdbc.queryForObject("""
				INSERT INTO public.faq_category(name) VALUES ('상담 인계 테스트')
				ON CONFLICT (name) WHERE deleted_at IS NULL DO UPDATE SET name=excluded.name RETURNING id
				""", Long.class);
		return jdbc.queryForObject("INSERT INTO public.faq(category_id,question,answer,admin_id) VALUES (?,?,?,99) RETURNING id",
				Long.class, category, question, answer);
	}
	@Test void handoffIgnoresForgedDisplayTextAndResolvesCurrentFaqOnServer() throws Exception {
		long faqId = createFaq("서버 FAQ 질문", "서버 FAQ 답변");
		var request = Map.of("handoffId", UUID.randomUUID(), "conversationId", OWN_CONVERSATION,
				"faqIds", List.of(faqId), "displayedTurns", List.of(
						Map.of("question", "이전 요금 문의", "answer", "위조 AI 답변"),
						Map.of("question", "위조 FAQ 질문", "answer", "위조 FAQ 답변")));
		var body = mvc.perform(post("/counsel/requests").header("Authorization", token("customer"))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(request)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JSON.readTree(body).path("data").path("sessionId").asText());
		sessions.updateAvailability(counselor, "AVAILABLE");
		assertThat(sessions.getHistory(counselor, id, 0, 100)).extracting(item -> item.content())
				.containsExactly("이전 요금 문의", "이전 AI 답변", "이전 추가 문의", "추가 AI 답변", "서버 FAQ 질문", "서버 FAQ 답변");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.session_history WHERE source='SCREEN'", Integer.class)).isZero();
	}
	@Test void handoffSkipsMissingAndDeletedFaqsAndKeepsAvailableFaqs() throws Exception {
		long deletedId = createFaq("삭제된 FAQ", "노출하면 안 되는 답변");
		jdbc.update("UPDATE public.faq SET deleted_at=now() WHERE id=?", deletedId);
		long availableId = createFaq("유효한 FAQ", "서버 FAQ 답변");
		mvc.perform(post("/counsel/requests").header("Authorization", token("customer"))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(
						new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of(deletedId, Long.MAX_VALUE, availableId)))))
				.andExpect(status().isOk());
		var room = sessions.getCurrentSession(customer);
		assertThat(sessions.getHistory(customer, room.sessionId(), 0, 100)).extracting(item -> item.content())
				.contains("유효한 FAQ", "서버 FAQ 답변", "이전 AI 답변")
				.doesNotContain("삭제된 FAQ", "노출하면 안 되는 답변");
	}
	@Test void handoffRejectsInvalidFaqIdsWithoutCreatingSession() throws Exception {
		int conversations = jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class);
		for (var ids : List.of("[-1]", "[null]", JSON.writeValueAsString(Collections.nCopies(101, 1)))) {
			mvc.perform(post("/counsel/requests").header("Authorization", token("customer"))
					.contentType(MediaType.APPLICATION_JSON).content("{\"handoffId\":\"" + UUID.randomUUID() + "\",\"faqIds\":" + ids + "}"))
					.andExpect(status().isBadRequest());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class)).isEqualTo(conversations);
	}
	@Test void seedReadinessRequiresWorkingEmbeddingAndActiveVectors() {
		seeder.verifyReady();
		assertThat(EMBEDDING_CALLS).hasValue(1);
		jdbc.update("UPDATE counseling.safety_filter_vectors SET active=false");
		assertThatThrownBy(() -> seeder.verifyReady()).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors WHERE active", Integer.class)).isZero();
	}
	@Test void initialIndexUsesWorkerAndRedisAndCanRetryAfterWorkerOutage() throws Exception {
		jdbc.execute("TRUNCATE counseling.guard_profiles CASCADE");
		var transport = new com.ubot.counsel.safety.client.WorkerEmbeddingTransport(redis, counselProperties.guard(), JSON);
		var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
		beans.addBean("transport", transport);
		var client = new EmbeddingClient(counselProperties, JSON,
				beans.getBeanProvider(com.ubot.counsel.safety.client.WorkerEmbeddingTransport.class), "worker");
		var profiles = new SafetyProfileService(jdbc, transactionTemplate, client, counselProperties, sentenceRepository);
		var model = new com.ubot.embedding.client.OpenAiCompatibleEmbeddingClient(
				org.springframework.web.client.RestClient.builder()
						.baseUrl("http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/v1").build(),
				counselProperties.guard().model());
		var worker = new com.ubot.embedding.worker.CounselEmbeddingWorker(redis, model, JSON);
		assertThatThrownBy(profiles::seedProfile).isInstanceOf(EmbeddingClient.Failure.class).hasMessage("WORKER_TIMEOUT");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.guard_profiles", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors", Integer.class)).isZero();
		worker.start();
		try {
			profiles.seedProfile();
			profiles.verifyReady();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors WHERE active", Integer.class)).isEqualTo(50);
			int calls = EMBEDDING_CALLS.get();
			profiles.seedProfile();
			assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
		} finally { worker.stop(); }
		assertThatThrownBy(profiles::verifyReady).isInstanceOf(EmbeddingClient.Failure.class).hasMessage("WORKER_TIMEOUT");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_vectors WHERE active", Integer.class)).isEqualTo(50);
	}
	@Test void handoffCopiesOwnedHistoryAndServerFaqWithoutDuplicatingSavedTurns() throws Exception {
		long faqId = createFaq("화면 FAQ 질문", "화면 FAQ 답변");
		var request = new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of(faqId));
		var body = mvc.perform(post("/counsel/requests").header("Authorization", token("customer"))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(request)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.conversationId").value(OWN_CONVERSATION.toString()))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JSON.readTree(body).path("data").path("sessionId").asText());
		var history = sessions.getHistory(customer, id, 0, 100);
		assertThat(history).hasSize(6);
		assertThat(history.stream().map(item -> item.content()).toList())
				.contains("이전 요금 문의", "이전 AI 답변", "이전 추가 문의", "추가 AI 답변", "화면 FAQ 질문", "화면 FAQ 답변");
		assertThat(history).allSatisfy(item -> assertThat(item.source()).isEqualTo("SAVED"));
		sessions.updateAvailability(counselor, "AVAILABLE");
		assertThat(sessions.getHistory(counselor, id, 0, 100)).isEqualTo(history);
		sessions.endSession(customer, id);
		assertThat(sessions.getHistory(counselor, id, 0, 100)).isEqualTo(history);
		assertThat(sessions.getConversationSession(customer, OWN_CONVERSATION).sessionId()).isEqualTo(id);
		assertThat(sessions.getConversationSession(new Caller(2, "USER"), OWN_CONVERSATION)).isNull();
		assertThatThrownBy(() -> sessions.getHistory(new Caller(2, "USER"), id, 0, 100)).isInstanceOf(CounselException.class);
		assertThatThrownBy(() -> sessions.getHistory(new Caller(11, "COUNSELOR"), id, 0, 100)).isInstanceOf(CounselException.class);
		assertThatThrownBy(() -> sessions.getHistory(administrator, id, 0, 100)).isInstanceOf(CounselException.class);
	}
	@Test void handoffRejectsSomeoneElsesConversationAndGuestsBeforeCreatingSession() throws Exception {
		var request = new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of());
		mvc.perform(post("/counsel/requests").header("Authorization", token("stranger"))
				.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(request)))
				.andExpect(status().isNotFound());
		mvc.perform(post("/counsel/requests").contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(request))).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isZero();
	}
	@Test void handoffRetriesAreIdempotentEvenAfterEndAndDifferentConversationCannotReplaceActiveRoom() {
		var request = new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of());
		var room = sessions.createHandoff(customer, request);
		assertThat(sessions.createHandoff(customer, request).sessionId()).isEqualTo(room.sessionId());
		assertThatThrownBy(() -> sessions.createHandoff(customer,
				new HandoffRequestDto(UUID.randomUUID(), UUID.randomUUID(), List.of())))
				.isInstanceOf(CounselException.class);
		assertThatThrownBy(() -> sessions.createHandoff(new Caller(2, "USER"), request))
				.isInstanceOf(CounselException.class);
		sessions.endSession(customer, room.sessionId());
		assertThat(sessions.createHandoff(customer, request).status()).isEqualTo("ENDED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isEqualTo(1);
	}
	@Test void historyPaginationReturnsAllRowsAndReassignmentRevokesPreviousCounselor() {
		var turns = new ArrayList<Long>();
		for (int i = 0; i < 130; i++) turns.add(createFaq("화면 질문 " + i, "화면 답변 " + i));
		var room = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), null, turns));
		sessions.updateAvailability(counselor, "AVAILABLE");
		assertThat(sessions.getHistory(counselor, room.sessionId(), 0, 100)).hasSize(100);
		assertThat(sessions.getHistory(counselor, room.sessionId(), 100, 100)).hasSize(100);
		assertThat(sessions.getHistory(counselor, room.sessionId(), 200, 100)).hasSize(60)
				.last().satisfies(item -> assertThat(item.content()).isEqualTo("화면 답변 129"));
		jdbc.update("UPDATE counseling.counselor_presence SET last_heartbeat_at=now()-interval '200 seconds' WHERE user_id=10");
		sessions.updateAvailability(new Caller(11, "COUNSELOR"), "AVAILABLE");
		assertThatThrownBy(() -> sessions.getHistory(counselor, room.sessionId(), 0, 100)).isInstanceOf(CounselException.class);
		assertThat(sessions.getHistory(new Caller(11, "COUNSELOR"), room.sessionId(), 200, 100)).hasSize(60);
	}
	@Test void contextFailureRollsBackSessionAndReopenedCounselIncludesOnlyDeliveredMessages() {
		jdbc.execute("ALTER TABLE counseling.session_history ADD CONSTRAINT reject_history_test CHECK (content <> '이전 AI 답변')");
		try {
			assertThatThrownBy(() -> sessions.createHandoff(customer,
					new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of())))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isZero();
		} finally { jdbc.execute("ALTER TABLE counseling.session_history DROP CONSTRAINT reject_history_test"); }
		var room = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of()));
		sessions.updateAvailability(counselor, "AVAILABLE");
		messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "이전 사람 상담 문의");
		messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "당신은 바보예요.");
		sessions.endSession(customer, room.sessionId());
		var next = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, List.of()));
		assertThat(sessions.getHistory(counselor, next.sessionId(), 0, 100).stream().map(item -> item.content()).toList())
				.contains("이전 사람 상담 문의").doesNotContain("당신은 바보예요.");
	}
	@Test void continuationAfterReloadKeepsServerFaqAndAllowedHumanMessages() {
		var first = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), null,
				List.of(createFaq("화면 FAQ", "화면 답변"))));
		sessions.updateAvailability(counselor, "AVAILABLE");
		messages.sendMessage(customer, first.sessionId(), UUID.randomUUID(), "이어서 문의합니다");
		messages.sendMessage(customer, first.sessionId(), UUID.randomUUID(), "당신은 바보예요.");
		sessions.endSession(customer, first.sessionId());
		var request = new HandoffRequestDto(UUID.randomUUID(), null, first.sessionId(), List.of());
		assertThatThrownBy(() -> sessions.createHandoff(new Caller(2, "USER"), request)).isInstanceOf(CounselException.class);
		var next = sessions.createHandoff(customer, request);
		assertThat(sessions.getHistory(counselor, next.sessionId(), 0, 100).stream().map(item -> item.content()).toList())
				.containsExactly("화면 FAQ", "화면 답변", "이어서 문의합니다");
		assertThat(sessions.createHandoff(customer, request).sessionId()).isEqualTo(next.sessionId());
	}
	@Test void continuationLoadsAiAndFaqCreatedAfterEndAndAllPreviousCounsel() {
		var first = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION,
				List.of(createFaq("이전 FAQ", "이전 FAQ 답변"))));
		sessions.updateAvailability(counselor, "AVAILABLE");
		messages.sendMessage(counselor, first.sessionId(), UUID.randomUUID(), "이전 상담 안내");
		sessions.endSession(customer, first.sessionId());
		jdbc.update("""
				INSERT INTO public.question_log(user_id,conversation_id,user_question,llm_question,sub_question_index)
				SELECT 1,conversation_id,'종료 후 AI 질문','종료 후 AI 답변',1
				FROM public.conversations WHERE public_id=?
				""", OWN_CONVERSATION);
		var second = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), null, first.sessionId(),
				List.of(createFaq("종료 후 FAQ", "종료 후 FAQ 답변"))));
		assertThat(sessions.getHistory(counselor, second.sessionId(), 0, 100)).extracting(item -> item.content())
				.contains("이전 AI 답변", "종료 후 AI 질문", "종료 후 AI 답변", "이전 FAQ", "이전 FAQ 답변",
						"종료 후 FAQ", "종료 후 FAQ 답변", "이전 상담 안내");
	}
	@Test void severityEditKeepsCurrentVectorAndBlockDecisionWithoutReindex() {
		var created = safetySentences.createSentence(administrator,
				new SafetySentenceCreateRequestDto("당신은 사고력이 전혀 없는 사람이네요.", 2));
		var indexed = safetySentences.indexSentence(administrator, created.sentenceId(), 1);
		int before = EMBEDDING_CALLS.get();
		var updated = safetySentences.updateSentence(administrator, created.sentenceId(),
				new SafetySentenceUpdateRequestDto(1L, created.content(), 3, true));
		assertThat(updated.applied()).isTrue();
		assertThat(updated.vectorId()).isEqualTo(indexed.vectorId());
		assertThat(updated.revision()).isEqualTo(2);
		assertThat(EMBEDDING_CALLS.get()).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT active FROM counseling.safety_filter_vectors WHERE id=?", Boolean.class, indexed.vectorId())).isTrue();
		assertThat(guard.checkMessage(created.content()).status()).isEqualTo("BLOCKED");
		var disabled = safetySentences.updateSentence(administrator, created.sentenceId(),
				new SafetySentenceUpdateRequestDto(2L, created.content(), 3, false));
		assertThat(disabled.applied()).isFalse();
		assertThat(jdbc.queryForObject("SELECT active FROM counseling.safety_filter_vectors WHERE id=?", Boolean.class, indexed.vectorId())).isFalse();
	}
	@Test void counselOnlyConversationAppearsInExistingListAndRetriesDoNotCreateExtraConversations() throws Exception {
		var request = new HandoffRequestDto(UUID.randomUUID(), null, List.of());
		var room = sessions.createHandoff(customer, request);
		assertThat(room.conversationId()).isNotNull();
		assertThat(sessions.createHandoff(customer, request).conversationId()).isEqualTo(room.conversationId());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class)).isEqualTo(2);
		mvc.perform(get("/conversations/me").header("Authorization", token("user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].conversationId").value(room.conversationId().toString()))
				.andExpect(jsonPath("$.data.content[0].title").value("상담사 상담"))
				.andExpect(jsonPath("$.data.content[0].hasCounsel").value(true));
		sessions.updateAvailability(counselor, "AVAILABLE");
		jdbc.update("UPDATE public.conversations SET updated_at=now()-interval '1 day' WHERE public_id=?", room.conversationId());
		messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "상담으로 시작한 문의");
		messages.sendMessage(counselor, room.sessionId(), UUID.randomUUID(), "담당 상담사의 답변");
		messages.sendMessage(customer, room.sessionId(), UUID.randomUUID(), "당신은 바보예요.");
		mvc.perform(get("/conversations/me").header("Authorization", token("user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].title").value("상담으로 시작한 문의"))
				.andExpect(jsonPath("$.data.content[0].preview").value("담당 상담사의 답변"));
		sessions.endSession(customer, room.sessionId());
		assertThat(sessions.createHandoff(customer, request).status()).isEqualTo("ENDED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class)).isEqualTo(2);
		// 기존 AI 문답 API 계약은 그대로이며 상담만 있으면 빈 문답 배열을 반환함
		mvc.perform(get("/conversations/" + room.conversationId() + "/messages").header("Authorization", token("user")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
	}
	@Test void timelineCombinesOriginalTurnsAndRepeatedCounselWithoutSnapshotDuplicates() throws Exception {
		long faqId = createFaq("화면 전용 질문", "화면 전용 답변");
		var shown = List.of(faqId, faqId);
		var first = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), OWN_CONVERSATION, shown));
		sessions.updateAvailability(counselor, "AVAILABLE");
		messages.sendMessage(customer, first.sessionId(), UUID.randomUUID(), "첫 상담 문의");
		messages.sendMessage(counselor, first.sessionId(), UUID.randomUUID(), "첫 상담 답변");
		messages.sendMessage(customer, first.sessionId(), UUID.randomUUID(), "당신은 바보예요.");
		sessions.endSession(customer, first.sessionId());
		var second = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(),
				OWN_CONVERSATION, first.sessionId(), List.of()));
		messages.sendMessage(counselor, second.sessionId(), UUID.randomUUID(), "재상담 답변");
		sessions.endSession(customer, second.sessionId());
		var items = timeline.getTimeline(customer, OWN_CONVERSATION, null, 100).items();
		assertThat(items.stream().map(item -> item.content()).toList()).containsExactly(
				"이전 요금 문의", "이전 AI 답변", "이전 추가 문의", "추가 AI 답변",
				"화면 전용 질문", "화면 전용 답변", "화면 전용 질문", "화면 전용 답변",
				"상담사 연결 요청", "첫 상담 문의", "첫 상담 답변", "상담 종료",
				"상담사 연결 요청", "재상담 답변", "상담 종료");
		assertThat(items.stream().map(item -> item.id()).distinct().count()).isEqualTo(items.size());
		mvc.perform(get("/conversations/" + OWN_CONVERSATION + "/timeline").header("Authorization", token("stranger")))
				.andExpect(status().isNotFound());
		mvc.perform(get("/conversations/" + OWN_CONVERSATION + "/timeline").header("Authorization", token("counselor")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/conversations/" + OWN_CONVERSATION + "/timeline")).andExpect(status().isUnauthorized());
		mvc.perform(get("/conversations/" + OWN_CONVERSATION + "/timeline?limit=101").header("Authorization", token("user")))
				.andExpect(status().isBadRequest());
	}
	@Test void timelinePagesAllEntriesAndDoesNotRepeatSavedOriginsOnFreshHandoff() {
		var turns = new ArrayList<Long>();
		for (int i = 0; i < 130; i++) turns.add(createFaq("화면 질문 " + i, "화면 답변 " + i));
		var first = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), null, turns));
		sessions.endSession(customer, first.sessionId());
		var second = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), first.conversationId(), turns));
		sessions.endSession(customer, second.sessionId());
		var entries = new ArrayList<com.ubot.counsel.history.dto.response.ConversationTimelineResponseDto>();
		for (String after = null; ;) {
			var batch = timeline.getTimeline(customer, first.conversationId(), after, 100);
			entries.addAll(batch.items());
			after = batch.nextCursor();
			if (!batch.hasNext()) break;
		}
		assertThat(entries).hasSize(264);
		assertThat(entries.stream().map(item -> item.id()).distinct().count()).isEqualTo(264);
	}
	@Test void failedCounselOnlyHandoffDoesNotLeaveEmptyConversation() {
		int before = jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class);
		long faqId = createFaq("저장 실패", "답변");
		jdbc.execute("ALTER TABLE counseling.session_history ADD CONSTRAINT reject_history_test CHECK (content <> '저장 실패')");
		try {
			assertThatThrownBy(() -> sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), null,
					List.of(faqId))))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class)).isEqualTo(before);
		} finally { jdbc.execute("ALTER TABLE counseling.session_history DROP CONSTRAINT reject_history_test"); }
	}
	@Test void faqSelectionSurvivesReloadModificationAndDeletionAndIsHandedOffFromServer() throws Exception {
		long faqId = createFaq("선택 당시 질문", "선택 당시 답변");
		UUID selectionId = UUID.randomUUID();
		String body = "{\"selectionId\":\"" + selectionId + "\",\"faqId\":" + faqId
				+ ",\"question\":\"위조 질문\",\"answer\":\"위조 답변\"}";
		var result = mvc.perform(post("/conversations/faq-selections").header("Authorization", token("user"))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("선택 당시 답변"))
				.andReturn();
		UUID conversationId = UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).path("data").path("conversationId").asText());
		jdbc.update("UPDATE public.faq SET question='바뀐 질문',answer='바뀐 답변',deleted_at=now() WHERE id=?", faqId);
		mvc.perform(post("/conversations/faq-selections").header("Authorization", token("user"))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversation_faq_selections", Integer.class)).isEqualTo(1);
		mvc.perform(get("/conversations/" + conversationId + "/messages").header("Authorization", token("user")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].answer").value("선택 당시 답변"));
		mvc.perform(get("/conversations/me").header("Authorization", token("user")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].title").value("선택 당시 질문"));
		var room = sessions.createHandoff(customer, new HandoffRequestDto(UUID.randomUUID(), conversationId, List.of()));
		assertThat(sessions.getHistory(customer, room.sessionId(), 0, 100)).extracting(item -> item.content())
				.containsExactly("선택 당시 질문", "선택 당시 답변");
		assertThat(timeline.getTimeline(customer, conversationId, null, 100).items()).extracting(item -> item.content())
				.containsExactly("선택 당시 질문", "선택 당시 답변", "상담사 연결 요청");
		mvc.perform(post("/conversations/faq-selections").header("Authorization", token("stranger"))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
		mvc.perform(post("/conversations/faq-selections").header("Authorization", token("counselor"))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mvc.perform(post("/conversations/faq-selections").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
	}
	@Test void concurrentFaqSelectionRetriesCreateOneSnapshotAndOneConversation() throws Exception {
		var request = new com.ubot.conversation.dto.request.FaqSelectionRequestDto(UUID.randomUUID(), null,
				createFaq("동시 선택 질문", "동시 선택 답변"));
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = new ArrayList<Future<com.ubot.conversation.dto.response.FaqSelectionResponseDto>>();
			for (int i = 0; i < 5; i++) tasks.add(pool.submit(() -> faqSelections.save(1L, request)));
			var ids = new HashSet<UUID>();
			for (var task : tasks) ids.add(task.get(10, TimeUnit.SECONDS).conversationId());
			assertThat(ids).hasSize(1);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversation_faq_selections", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.conversations", Integer.class)).isEqualTo(2);
	}
	@Test void timelineCursorDoesNotSkipRemainingEntriesWhenEarlierRowsAreDeletedOrInserted() {
		var first = timeline.getTimeline(customer, OWN_CONVERSATION, null, 2);
		assertThat(first.hasNext()).isTrue();
		var expected = timeline.getTimeline(customer, OWN_CONVERSATION, first.nextCursor(), 100);
		jdbc.update("DELETE FROM public.question_log WHERE id=(SELECT min(id) FROM public.question_log)");
		jdbc.update("""
				INSERT INTO public.question_log(user_id,conversation_id,user_question,llm_question,created_at)
				SELECT 1,conversation_id,'뒤늦게 저장한 과거 질문','과거 답변',now()-interval '1 day'
				FROM public.conversations WHERE public_id=?
				""", OWN_CONVERSATION);
		var remaining = timeline.getTimeline(customer, OWN_CONVERSATION, first.nextCursor(), 100);
		assertThat(remaining.items()).extracting(item -> item.id())
				.containsExactlyElementsOf(expected.items().stream().map(item -> item.id()).toList());
		assertThat(remaining.hasNext()).isFalse();
		assertThat(remaining.nextCursor()).isNull();
	}
	@Test void automaticIndexProcessesCreateAndContentEditAndRetriesWorkerFailure() {
		var created = safetySentences.createSentence(administrator, new SafetySentenceCreateRequestDto("자동 색인 문장", 2));
		safetySentences.indexPendingSentences();
		var indexed = safetySentences.getSentence(administrator, created.sentenceId());
		assertThat(indexed.applied()).isTrue();
		safetySentences.updateSentence(administrator, created.sentenceId(), new SafetySentenceUpdateRequestDto(1L, "느린 자동 색인", 2, true));
		entered = new CountDownLatch(1); release = new CountDownLatch(1);
		try {
			safetySentences.indexPendingSentences();
			assertThat(safetySentences.getSentence(administrator, created.sentenceId()).indexStatus()).isEqualTo("FAILED");
			int calls = EMBEDDING_CALLS.get();
			safetySentences.indexPendingSentences();
			assertThat(EMBEDDING_CALLS.get()).isEqualTo(calls);
		} finally { release.countDown(); }
		jdbc.update("UPDATE counseling.safety_sentences SET updated_at=now()-interval '31 seconds' WHERE id=?", created.sentenceId());
		safetySentences.indexPendingSentences();
		var retried = safetySentences.getSentence(administrator, created.sentenceId());
		assertThat(retried.applied()).isTrue();
		assertThat(retried.vectorId()).isNotEqualTo(indexed.vectorId());
	}
	@Test void reassignmentPreservesOriginalWaitingOrder() {
		var first = active();
		var second = sessions.createSession(new Caller(2, "USER"));
		jdbc.update("UPDATE counseling.sessions SET waiting_since=now()-interval '5 minutes' WHERE id=?", first.sessionId());
		jdbc.update("UPDATE counseling.counselor_presence SET last_heartbeat_at=now()-interval '200 seconds' WHERE user_id=10");
		sessions.updateAvailability(new Caller(11, "COUNSELOR"), "AVAILABLE");
		assertThat(sessions.getSession(customer, first.sessionId()).counselorId()).isEqualTo(11L);
		assertThat(sessions.getSession(new Caller(2, "USER"), second.sessionId()).status()).isEqualTo("WAITING");
	}
	@Test void retentionDeletesInactiveCounselAndRejectedAttemptsButKeepsOpenConversation() {
		var closed = active();
		messages.sendMessage(customer, closed.sessionId(), UUID.randomUUID(), "종료 상담 기록");
		sessions.endSession(customer, closed.sessionId());
		jdbc.update("UPDATE public.conversations SET updated_at=now()-interval '4 months' WHERE public_id=?", closed.conversationId());
		var open = sessions.createSession(customer);
		var allowed = messages.sendMessage(customer, open.sessionId(), UUID.randomUUID(), "진행 중인 상담 기록");
		var rejected = messages.sendMessage(customer, open.sessionId(), UUID.randomUUID(), "당신은 바보예요.");
		jdbc.update("UPDATE counseling.messages SET created_at=now()-interval '4 months' WHERE session_id=?", open.sessionId());
		jdbc.update("UPDATE public.conversations SET updated_at=now()-interval '4 months' WHERE public_id=?", open.conversationId());
		jdbc.execute("CALL purge_expired_data()");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions WHERE id=?", Integer.class, closed.sessionId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages WHERE session_id=?", Integer.class, closed.sessionId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages WHERE id=?", Integer.class, rejected.messageId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_events WHERE message_id=?", Integer.class, rejected.messageId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages WHERE id=?", Integer.class, allowed.messageId())).isEqualTo(1);
		assertThat(sessions.getSession(customer, open.sessionId()).status()).isEqualTo("ACTIVE");
	}
	@Test void withdrawnCounselorDoesNotBlockUserPurgeOrEraseCustomerHistory() {
		var room = active();
		messages.sendMessage(counselor, room.sessionId(), UUID.randomUUID(), "이전 상담사의 답변");
		jdbc.update("UPDATE public.users SET deleted_at=now()-interval '4 months' WHERE user_id=10");
		jdbc.execute("CALL purge_expired_data()");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users WHERE user_id=10", Integer.class)).isZero();
		assertThat(sessions.getSession(customer, room.sessionId()).status()).isEqualTo("ENDED");
		assertThat(sessions.getSession(customer, room.sessionId()).counselorId()).isNull();
		assertThat(messages.getMessages(customer, room.sessionId(), 0, 100)).singleElement()
				.satisfies(item -> { assertThat(item.mine()).isFalse(); assertThat(item.content()).isEqualTo("이전 상담사의 답변"); });
		jdbc.update("UPDATE public.users SET deleted_at=now()-interval '4 months' WHERE user_id=1");
		jdbc.execute("CALL purge_expired_data()");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.session_history", Integer.class)).isZero();
	}
}
