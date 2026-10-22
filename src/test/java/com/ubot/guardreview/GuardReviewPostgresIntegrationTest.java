package com.ubot.guardreview;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.service.ChatPromptInjectionService;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.*;
import com.ubot.guardreview.controller.AdminReviewController;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.guardreview.scheduler.ReviewWordCacheRefresh;
import com.ubot.guardreview.scheduler.ReviewScheduler;
import com.ubot.guardreview.service.*;
import com.ubot.guardreview.worker.ReviewWorker;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import com.ubot.notification.entity.Notification;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.notification.service.NotificationService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL/Flyway/JPA/JDBC/MVC/transactions; only identity and model responses are synthetic.
 * No application scan, production properties, schedulers, Redis, HTTP model client or production datasource.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardReviewPostgresIntegrationTest {
	static final JsonMapper JSON = JsonMapper.builder().build();
	static DataSource isolated;
	static final FakeModel MODEL = new FakeModel();
	AnnotationConfigWebApplicationContext api, workerContext;
	JdbcTemplate db;
	MockMvc mvc;
	ReviewRepository repo;
	ReviewService reviews;
	ReviewWorker worker;
	ReviewWorkService work;
	ReviewValidationService validator;
	Path auditFile;
	org.springframework.context.annotation.AnnotationConfigApplicationContext databaseContext;
	@org.junit.jupiter.api.io.TempDir
	static Path artifacts;
	final CustomUserDetails admin = identity(99, UserRole.ADMIN), otherAdmin = identity(100, UserRole.ADMIN),
			normal = identity(1, UserRole.USER);

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@EnableWebSecurity
	@EnableTransactionManagement
	@EnableJpaRepositories(basePackageClasses = {ForbiddenWordRepository.class, NotificationRepository.class})
	@Import({ReviewRepository.class, ReviewService.class, ReviewCatalogService.class, ForbiddenWordCatalogLock.class,
			ReviewValidationService.class, ReviewWorkService.class, ReviewAlertService.class,
			ReviewRetentionService.class, ReviewWordCacheRefresh.class, ReviewScheduler.class, ReviewWorker.class,
			ReviewLeaseService.class, AdminReviewController.class, GlobalExceptionHandler.class,
			ForbiddenWordService.class, ForbiddenWordFilterService.class, NotificationService.class,
			ChatPromptInjectionService.class})
	static class TestConfig {
		@Bean
		DataSource dataSource() {
			return isolated;
		}
		@Bean
		JdbcTemplate jdbcTemplate(DataSource ds) {
			return new JdbcTemplate(ds);
		}
		@Bean
		LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
			var f = new LocalContainerEntityManagerFactoryBean();
			f.setDataSource(ds);
			f.setPackagesToScan(ForbiddenWord.class.getPackageName(), Notification.class.getPackageName());
			f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
			f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false"));
			return f;
		}
		@Bean
		PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf, DataSource ds) {
			var t = new JpaTransactionManager(emf);
			t.setDataSource(ds);
			return t;
		}
		@Bean
		ReviewAnalyzer analyzer() {
			return new LlmReviewAnalyzer(new LlmService(MODEL));
		}
		@Bean
		ReviewEmbedding embedding() {
			return new ReviewEmbedding(null, null) {
				@Override
				public com.ubot.guardreview.model.ReviewModels.Vector embed(String text) {
					if (MODEL.embeddingFails)
						throw new IllegalStateException("synthetic embedding failure");
					float[] v = new float[1024];
					v[MODEL.profile.equals("fake-v1") ? 0 : 1] = 1;
					return new com.ubot.guardreview.model.ReviewModels.Vector(MODEL.profile, v);
				}
			};
		}
		@Bean
		SecurityFilterChain security(HttpSecurity http) throws Exception {
			// Authentication is supplied by the test, /admin role check and controller are real.
			return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().hasRole("ADMIN"))
					.exceptionHandling(e -> e.authenticationEntryPoint((q, s, x) -> s.sendError(401))).build();
		}
	}
	static class FakeModel implements LlmClient {
		final AtomicInteger calls = new AtomicInteger();
		final List<String> inputs = new CopyOnWriteArrayList<>();
		volatile String mode = "OK", profile = "fake-v1";
		volatile boolean embeddingFails;
		public String getModelName() {
			return "FAKE-ONLY-NO-NETWORK";
		}
		public LlmResponseDto generateAnswer(LlmRequestDto request) {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(request.hasTools()).isFalse();
			assertThat(request.hasResponseSchema()).isTrue();
			calls.incrementAndGet();
			var input = JSON.readTree(request.messages().getLast().content());
			inputs.add(input.toString());
			if (mode.equals("UNAVAILABLE"))
				throw new com.ubot.llm.exception.LlmException(
						com.ubot.llm.exception.LlmErrorCode.LLM_SERVICE_UNAVAILABLE);
			if (mode.equals("FAIL"))
				throw new IllegalStateException("synthetic model failure");
			if (mode.equals("MALFORMED"))
				return new LlmResponseDto("not json");
			var list = new ArrayList<Suggestion>();
			if (mode.equals("FORGED"))
				list.add(new Suggestion(Kind.WORD, "ABUSE", "자료에없는문구", "가짜 응답", Stage.INPUT, "HARMFUL_REQUEST"));
			else {
				String q = input.path("question").asText(), a = input.path("answer").asText();
				for (String word : List.of("실험금칙표현", "두번째실험표현", "검증용모욕표현", "요금제"))
					if (q.contains("후보:" + word))
						list.add(new Suggestion(Kind.WORD, "ABUSE", word, "독립 DB 시험 후보", Stage.INPUT,
								"HARMFUL_REQUEST"));
				for (String warning : List.of("위험문장A", "위험문장B", "위험문장C"))
					if (q.contains(warning))
						list.add(new Suggestion(Kind.WARNING, "THREAT", warning, "문맥 검토 시험", Stage.INPUT,
								"HARMFUL_REQUEST"));
				if (a.contains("두번째위험출력"))
					list.add(new Suggestion(Kind.WARNING, "ABUSE", "두번째위험출력", "추가 출력 검토 시험", Stage.OUTPUT,
							"HARMFUL_OUTPUT"));
				if (a.contains("위험출력문장"))
					list.add(new Suggestion(Kind.WARNING, "THREAT", "위험출력문장", "출력 검토 시험", Stage.OUTPUT,
							"HARMFUL_OUTPUT"));
			}
			return new LlmResponseDto(JSON.writeValueAsString(Map.of("suggestions", list)));
		}
		void reset() {
			calls.set(0);
			inputs.clear();
			mode = "OK";
			profile = "fake-v1";
			embeddingFails = false;
		}
	}
	static CustomUserDetails identity(long id, UserRole role) {
		return new CustomUserDetails(User.builder().id(id).email("it" + id + "@example.invalid").role(role)
				.hashedPassword("unused").name("Synthetic").build());
	}

	@BeforeAll
	void migrateAndStart() throws Exception {
		databaseContext = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
				com.ubot.PgvectorTestConfiguration.class);
		var postgres = databaseContext.getBean(org.testcontainers.postgresql.PostgreSQLContainer.class);
		postgres.start();
		isolated = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		auditFile = artifacts.resolve("flow-results.jsonl");
		Files.writeString(auditFile, "");
		db = new JdbcTemplate(isolated);
		assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo(postgres.getDatabaseName());
		var first = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").target("45").load();
		first.migrate();
		db.update(
				"INSERT INTO users(user_id,email,password_hash,name,role) VALUES(987,'migration@example.invalid','unused','migration sentinel','ADMIN')");
		db.update(
				"INSERT INTO forbidden_words(word,status,created_at,updated_at) VALUES('migration-sentinel','ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP)");
		db.update(
				"INSERT INTO notifications(user_id,type,title,message,created_at) VALUES(987,'BILLING_OVERDUE','sentinel','preserve',LOCALTIMESTAMP)");

		Flyway.configure().dataSource(isolated).locations("classpath:db/migration").target("48").load().migrate();
		String legacyKey = "none:987:legacy-private-key";
		long legacyRun = db.queryForObject(
				"INSERT INTO guard_review_runs(kind,analysis_mode) VALUES('ANALYSIS','FULL') RETURNING id", Long.class);
		long legacyCandidate = db.queryForObject(
				"INSERT INTO guard_review_candidates(kind,category,expression,reason,intent,normalized_hash) VALUES('WARNING','THREAT','legacy warning','migration check','HARMFUL_REQUEST','legacy-hash') RETURNING id",
				Long.class);
		db.update(
				"INSERT INTO guard_review_items(run_id,source_key,source_fingerprint,answer_snapshot) VALUES(?,?,'legacy-fingerprint','legacy answer')",
				legacyRun, legacyKey);
		db.update(
				"INSERT INTO guard_review_findings(candidate_id,first_run_id,source_key,source_fingerprint,stage,category) VALUES(?,?,?,'legacy-fingerprint','INPUT','THREAT')",
				legacyCandidate, legacyRun, legacyKey);
		String alreadyHashed = sourceKeyHash("already hashed");
		db.update("INSERT INTO guard_review_items(run_id,source_key,source_fingerprint) VALUES(?,?,'already-hashed')",
				legacyRun, alreadyHashed);

		var flyway = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").load();
		var migrated = flyway.migrate();
		flyway.validate();
		assertThat(flyway.info().current().getVersion().toString()).isEqualTo("49");

		assertThat(db.queryForObject(
				"SELECT source_key FROM guard_review_items WHERE source_fingerprint='legacy-fingerprint'",
				String.class)).isEqualTo(sourceKeyHash(legacyKey));
		assertThat(db.queryForObject(
				"SELECT source_key FROM guard_review_items WHERE source_fingerprint='already-hashed'", String.class))
				.isEqualTo(alreadyHashed);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM guard_review_findings f JOIN guard_review_items i ON i.run_id=f.first_run_id AND i.source_key=f.source_key AND i.source_fingerprint=f.source_fingerprint",
				Integer.class)).isEqualTo(1);

		assertThat(count("users WHERE user_id=987")).isEqualTo(1);
		assertThat(count("forbidden_words WHERE word='migration-sentinel'")).isEqualTo(1);
		assertThat(count("notifications WHERE title='sentinel'")).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'guard_review_%'",
				Integer.class)).isEqualTo(6);
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		Files.writeString(artifacts.resolve("migration-result.json"),
				JSON.writeValueAsString(Map.of("version", "49", "upgradeMigrations", migrated.migrationsExecuted,
						"sixTables", true, "existingUserWordNotificationPreserved", true, "secondMigrateChanges", 0,
						"extensions", db.queryForList("SELECT extname,extversion FROM pg_extension ORDER BY extname"),
						"server", db.queryForObject("SHOW server_version", String.class))));
		api = context("api");
		workerContext = context("worker");
		mvc = MockMvcBuilders.webAppContextSetup(api).apply(springSecurity()).build();
		assertThat(api.getBeansOfType(ReviewWorker.class)).isEmpty();
		worker = workerContext.getBean(ReviewWorker.class);
		repo = api.getBean(ReviewRepository.class);
		reviews = api.getBean(ReviewService.class);
		work = workerContext.getBean(ReviewWorkService.class);
		validator = api.getBean(ReviewValidationService.class);
		assertThat(api.getBeansOfType(com.ubot.chat.job.worker.ChatJobWorker.class)).isEmpty();
	}
	AnnotationConfigWebApplicationContext context(String role) {
		var c = new AnnotationConfigWebApplicationContext();
		c.setServletContext(new MockServletContext());
		c.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("isolated-only", Map.of("app.role", role)));
		c.register(TestConfig.class);
		c.refresh();
		return c;
	}
	@AfterAll
	void close() {
		if (workerContext != null)
			workerContext.close();
		if (api != null)
			api.close();
		if (databaseContext != null)
			databaseContext.close();
	}
	@BeforeEach
	void reset() {
		db.execute(
				"TRUNCATE guard_review_history,guard_review_findings,guard_review_candidates,guard_review_items,guard_review_runs,notifications,question_log,answer_attempts_history,conversations,users,forbidden_words RESTART IDENTITY CASCADE");
		db.update(
				"INSERT INTO users(user_id,email,password_hash,name,role) VALUES(1,'user@example.invalid','unused','User','USER'),(99,'admin@example.invalid','unused','Admin','ADMIN'),(100,'admin2@example.invalid','unused','Admin2','ADMIN')");
		// TRUNCATE users CASCADE also clears the FK-linked settings table.
		db.update(
				"INSERT INTO guard_review_settings(id,enabled,interval_minutes,grouping_threshold,revision) VALUES(1,false,1440,0.95,1)");
		MODEL.reset();
		for (var c : List.of(api, workerContext))
			c.getBean(ForbiddenWordFilterService.class).refreshForbiddenWordCache();
	}
	int count(String from) {
		return db.queryForObject("SELECT count(*) FROM " + from, Integer.class);
	}
	static String sourceKeyHash(String key) {
		try {
			return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
	long source(String key, String question, String answer) {
		return source(key, question, answer, 1);
	}
	long source(String key, String question, String answer, int attempt) {
		long id = db.queryForObject(
				"INSERT INTO answer_attempts_history(user_id,question,attempt_count,status,idempotency_key) VALUES(1,?,?,'SUCCESS',?) RETURNING attempt_id",
				Long.class, question, attempt, key);
		if (answer != null)
			answer(id, question, answer);
		return id;
	}
	void answer(long id, String q, String a) {
		if (db.update(
				"UPDATE question_log SET user_question=?,llm_question=? WHERE attempt_id=? AND sub_question_index=1", q,
				a, id) == 0)
			db.update("INSERT INTO question_log(user_id,user_question,llm_question,attempt_id) VALUES(1,?,?,?)", q, a,
					id);
	}
	JsonNode request(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
		return request(request, body, expected, admin);
	}
	JsonNode request(MockHttpServletRequestBuilder request, Object body, int expected, CustomUserDetails who)
			throws Exception {
		if (who != null)
			request.with(user(who));
		if (body != null)
			request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
		var r = mvc.perform(request).andReturn().getResponse();
		assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(expected);
		return r.getContentAsByteArray().length == 0 ? JSON.readTree("{}") : JSON.readTree(r.getContentAsString());
	}
	long full() throws Exception {
		return request(post("/admin/guard-review/analysis"), null, 200).path("data").asLong();
	}
	void drain(long id) {
		for (int i = 0; i < 50; i++) {
			Run r = repo.run(id);
			if (Set.of("COMPLETED", "FAILED").contains(r.status())) {
				var report = new LinkedHashMap<String, Object>();
				report.put("run", id);
				report.put("kind", r.kind());
				report.put("mode", r.analysisMode());
				report.put("status", r.status());
				report.put("processed", r.processed());
				report.put("total", r.total());
				if (r.kind().equals("VALIDATION") && r.status().equals("COMPLETED")) {
					var v = JSON.readValue(r.result(), Validation.class);
					report.put("caseCount", v.cases().size());
					report.put("falsePositives", v.falsePositives());
					report.put("regressions", v.regressions());
					report.put("baselineFailures", v.baselineFailures());
					report.put("newDetections", v.newDetections());
				}
				try {
					Files.writeString(auditFile, JSON.writeValueAsString(report) + "\n", StandardOpenOption.APPEND);
				} catch (java.io.IOException e) {
					throw new IllegalStateException(e);
				}
				return;
			}
			worker.poll();
		}
		fail("Worker did not finish");
	}
	long word(String expression) {
		return db.queryForObject("SELECT id FROM guard_review_candidates WHERE expression=?", Long.class, expression);
	}
	List<Approval> selection(long... ids) {
		return Arrays.stream(ids).mapToObj(i -> new Approval(i, repo.candidate(i, false).revision())).toList();
	}
	long validateSelection(List<Approval> selection) throws Exception {
		return request(post("/admin/guard-review/validation"), Map.of("selection", selection), 200).path("data")
				.asLong();
	}
	void approve(long run, List<Approval> s, int expected) throws Exception {
		request(post("/admin/guard-review/approval"), Map.of("runId", run, "selection", s), expected);
	}
	long scheduled() {
		db.update("UPDATE guard_review_settings SET enabled=true,next_run_at=LOCALTIMESTAMP-interval '1 minute'");
		api.getBean(ReviewScheduler.class).tick();
		return db.queryForObject("SELECT max(id) FROM guard_review_runs", Long.class);
	}
	void age(long id, int days) {
		db.update(
				"UPDATE guard_review_runs SET created_at=LOCALTIMESTAMP-make_interval(days => ?),finished_at=LOCALTIMESTAMP-make_interval(days => ?) WHERE id=?",
				days, days, id);
	}

	@Test
	void administratorApiEnforcesRoleAndRevision() throws Exception {
		request(get("/admin/guard-review"), null, 401, null);
		request(get("/admin/guard-review"), null, 403, normal);
		assertThat(request(get("/admin/guard-review"), null, 200).path("data").path("retention").path("detailDays")
				.asInt()).isEqualTo(30);
		var body = Map.of("enabled", true, "intervalMinutes", 5, "groupingThreshold", 0.95, "revision", 1);
		request(patch("/admin/guard-review/settings"), body, 200);
		request(patch("/admin/guard-review/settings"), body, 409);
		request(patch("/admin/guard-review/settings"),
				Map.of("enabled", true, "intervalMinutes", 0, "groupingThreshold", 0.95, "revision", 2), 400);
		request(get("/admin/guard-review/candidates").param("kind", "WORD").param("size", "9"), null, 400);
	}
	@Test
	void fullApiWorkerCandidateWarningAndNotificationFlow() throws Exception {
		source("flow", "후보:실험금칙표현 위험문장A", "위험출력문장");
		long run = full();
		assertThat(repo.run(run).total()).isEqualTo(1);
		assertThat(repo.run(run).analysisMode()).isEqualTo("FULL");
		drain(run);
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
		assertThat(MODEL.calls.get()).isEqualTo(1);
		assertThat(count("guard_review_candidates")).isEqualTo(3);
		assertThat(count("forbidden_words")).isZero();
		assertThat(count("notifications")).isEqualTo(4); // 2 warnings x 2 administrators
		var list = request(get("/admin/guard-review/notifications"), null, 200).path("data");
		assertThat(list.size()).isEqualTo(2);
		long n = list.get(0).path("id").asLong();
		request(patch("/admin/guard-review/notifications/" + n + "/read"), null, 404, otherAdmin);
		request(patch("/admin/guard-review/notifications/" + n + "/read"), null, 200);
		assertThat(count("notifications WHERE read_at IS NOT NULL")).isEqualTo(1);
		long w = word("위험문장A");
		request(post("/admin/guard-review/validation"), Map.of("selection", selection(w)), 400);
		request(post("/admin/guard-review/candidates/" + w + "/decision"), Map.of("revision", 1, "action", "REVIEWED"),
				200);
		assertThat(count("forbidden_words")).isZero();
		assertThat(request(get("/admin/guard-review/candidates/" + w + "/findings"), null, 200).path("data").get(0)
				.path("question").asText()).contains("위험문장A");
	}
	@Test
	void editRequiresRetestThenAppliesUsingExistingWordServiceAndRefreshesOtherProcessCache() throws Exception {
		source("approve", "후보:실험금칙표현", null);
		drain(full());
		long c = word("실험금칙표현");
		var old = selection(c);
		long v = validateSelection(old);
		drain(v);
		request(patch("/admin/guard-review/candidates/" + c),
				Map.of("revision", 1, "expression", "두번째실험표현", "reason", "관리자 수정"), 200);
		approve(v, old, 409);
		assertThat(count("forbidden_words")).isZero();
		var current = selection(c);
		long changed = validateSelection(current);
		drain(changed);
		var result = JSON.readValue(repo.run(changed).result(), Validation.class);
		assertThat(result.cases()).hasSize(5168);
		assertThat(result.falsePositives()).isZero();
		assertThat(result.regressions()).isZero();
		approve(changed, current, 200);
		assertThat(count("forbidden_words WHERE status='ACTIVE'")).isEqualTo(1);
		assertThatThrownBy(() -> api.getBean(ForbiddenWordFilterService.class).validateForbiddenWord("두번째실험표현"))
				.isInstanceOf(RuntimeException.class);
		assertThatCode(() -> workerContext.getBean(ForbiddenWordFilterService.class).validateForbiddenWord("두번째실험표현"))
				.doesNotThrowAnyException();
		workerContext.getBean(ReviewWordCacheRefresh.class).refresh();
		assertThatThrownBy(
				() -> workerContext.getBean(ForbiddenWordFilterService.class).validateForbiddenWord("두번째실험표현"))
				.isInstanceOf(RuntimeException.class);
		request(post("/admin/guard-review/candidates/" + c + "/decision"), Map.of("revision", 2, "action", "DISABLE"),
				200);
		assertThat(count("forbidden_words WHERE status='INACTIVE'")).isEqualTo(1);
	}
	@Test
	void falseBlockingCandidateCannotBeApproved() throws Exception {
		source("fp", "후보:요금제", null);
		drain(full());
		var s = selection(word("요금제"));
		long v = validateSelection(s);
		drain(v);
		var result = JSON.readValue(repo.run(v).result(), Validation.class);
		assertThat(result.falsePositives()).isPositive();
		assertThat(result.passed()).isFalse();
		approve(v, s, 409);
		assertThat(count("forbidden_words")).isZero();
	}
	@Test
	void historicalWordFixtureReplaysAll5168CasesThroughApiWorkerAndApproval() throws Exception {
		String[] baseline;
		try (var input = new org.springframework.core.io.ClassPathResource("guard-review/baseline-words.json")
				.getInputStream()) {
			baseline = JSON.readValue(input.readAllBytes(), String[].class);
		}
		for (String word : baseline)
			db.update(
					"INSERT INTO forbidden_words(word,status,created_at,updated_at) VALUES(?,'ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP)",
					word);
		for (var c : List.of(api, workerContext))
			c.getBean(ForbiddenWordFilterService.class).refreshForbiddenWordCache();
		source("historical-fixture", "후보:검증용모욕표현", null);
		drain(full());
		var s = selection(word("검증용모욕표현"));
		long v = validateSelection(s);
		drain(v);
		var result = JSON.readValue(repo.run(v).result(), Validation.class);
		assertThat(result.cases()).hasSize(5168);
		assertThat(result.falsePositives()).isZero();
		assertThat(result.regressions()).isZero();
		// The only baseline mismatch is the deliberately added candidate detection fixture, resolved after application.
		assertThat(result.baselineFailures()).isEqualTo(1);
		assertThat(result.newDetections()).isEqualTo(1);
		assertThat(result.cases().stream().filter(CaseResult::baselineFailure))
				.allMatch(c -> c.id().equals("ATTACK-WORD") && c.newDetection());
		approve(v, s, 200);
		assertThat(count("forbidden_words")).isEqualTo(baseline.length + 1);
	}
	@Test
	void manualCatalogChangeInvalidatesPreviouslyPassedValidation() throws Exception {
		source("stale", "후보:실험금칙표현", null);
		drain(full());
		var s = selection(word("실험금칙표현"));
		long v = validateSelection(s);
		drain(v);
		api.getBean(ForbiddenWordService.class)
				.createForbiddenWord(new com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto("수동추가금칙어"));
		approve(v, s, 409);
		assertThat(count("forbidden_words WHERE word='실험금칙표현'")).isZero();
	}
	@Test
	void scheduledOnlyNewChangedAndLateAnswersAreAnalyzed() throws Exception {
		long original = source("incremental", "후보:실험금칙표현", null);
		long first = scheduled();
		drain(first);
		assertThat(repo.run(first).analysisMode()).isEqualTo("INCREMENTAL");
		assertThat(MODEL.calls.get()).isEqualTo(1);
		long empty = scheduled();
		drain(empty);
		assertThat(repo.run(empty).total()).isZero();
		assertThat(MODEL.calls.get()).isEqualTo(1);
		answer(original, "후보:실험금칙표현", "위험출력문장");
		long late = scheduled();
		drain(late);
		assertThat(repo.run(late).total()).isEqualTo(1);
		source("incremental", "후보:실험금칙표현", "위험출력문장", 2);
		long retry = scheduled();
		drain(retry);
		assertThat(repo.run(retry).total()).isZero();
		long unchanged = scheduled();
		drain(unchanged);
		assertThat(repo.run(unchanged).total()).isZero();
		source("new", "새 정상 질문", "정상 답변");
		long added = scheduled();
		drain(added);
		assertThat(repo.run(added).total()).isEqualTo(1);
	}
	@Test
	void workerUsesFrozenSnapshotAndNextIncrementalSeesChange() throws Exception {
		long id = source("freeze", "원래 질문", "원래 답변");
		long run = full();
		db.update("UPDATE answer_attempts_history SET question='변경 질문' WHERE attempt_id=?", id);
		answer(id, "변경 질문", "변경 답변");
		drain(run);
		assertThat(MODEL.inputs.getFirst()).contains("원래 질문", "원래 답변").doesNotContain("변경 질문");
		long next = scheduled();
		drain(next);
		assertThat(repo.run(next).total()).isEqualTo(1);
		assertThat(MODEL.inputs.getLast()).contains("변경 질문", "변경 답변");
	}
	@Test
	void failureIsNotSuccessfulCheckpointAndResumeUsesFrozenData() throws Exception {
		source("failure", "후보:실험금칙표현", null);
		MODEL.mode = "FAIL";
		long run = full();
		drain(run);
		assertThat(repo.run(run).status()).isEqualTo("FAILED");
		assertThat(count("guard_review_items WHERE status='COMPLETED'")).isZero();
		MODEL.mode = "OK";
		request(post("/admin/guard-review/runs/" + run + "/resume"), null, 200);
		drain(run);
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
		assertThat(count("guard_review_candidates")).isEqualTo(1);
		long empty = scheduled();
		drain(empty);
		assertThat(repo.run(empty).total()).isZero();
	}
	@Test
	void invalidModelJsonAndInventedExpressionsCannotCreateCandidates() throws Exception {
		source("bad-json", "정상 질문", null);
		MODEL.mode = "MALFORMED";
		long run = full();
		drain(run);
		assertThat(repo.run(run).status()).isEqualTo("FAILED");
		MODEL.mode = "FORGED";
		long other = scheduled();
		drain(other);
		assertThat(repo.run(other).total()).isEqualTo(1);
		assertThat(repo.run(other).status()).isEqualTo("FAILED");
		assertThat(count("guard_review_candidates")).isZero();
		assertThat(count("notifications")).isZero();
	}
	@Test
	void fullReanalysisDoesNotRepeatFindingsOrNotificationsButChangedContentDoes() throws Exception {
		long id = source("dedupe", "위험문장A", null);
		drain(full());
		assertThat(count("notifications")).isEqualTo(2);
		long repeat = full();
		drain(repeat);
		assertThat(repo.run(repeat).total()).isEqualTo(1);
		assertThat(count("guard_review_findings")).isEqualTo(1);
		assertThat(count("notifications")).isEqualTo(2);
		db.update("UPDATE answer_attempts_history SET question='위험문장A 변경된 질문' WHERE attempt_id=?", id);
		answer(id, "위험문장A 변경된 질문", "새로 생성한 모델 답변");
		drain(scheduled());
		assertThat(count("guard_review_candidates")).isEqualTo(1);
		assertThat(count("guard_review_findings")).isEqualTo(2);
		assertThat(count("notifications")).isEqualTo(4);
		worker.deliverAlerts();
		assertThat(count("notifications")).isEqualTo(4);
	}
	@Test
	void semanticGroupingRespectsIntentAndEmbeddingProfileAndFailureFallsBackToExact() throws Exception {
		source("a", "위험문장A", null);
		source("b", "위험문장B", null);
		source("out", "출력 확인", "위험출력문장");
		drain(full());
		assertThat(count("guard_review_candidates WHERE kind='WARNING'")).isEqualTo(2); // same vector, different intent is separate
		MODEL.profile = "fake-v2";
		source("c", "위험문장C", null);
		drain(scheduled());
		assertThat(count("guard_review_candidates")).isEqualTo(3);
		MODEL.embeddingFails = true;
		// Either A or B may represent the first semantic group; exercise a warning without an exact candidate.
		String withoutExact = List.of("위험문장A", "위험문장B").stream()
				.filter(expression -> !db.queryForObject(
						"SELECT EXISTS(SELECT 1 FROM guard_review_candidates WHERE expression=?)", Boolean.class,
						expression))
				.findFirst().orElseThrow();
		source("d", withoutExact + " 다른 문맥", null);
		drain(scheduled());
		assertThat(count("guard_review_candidates")).isEqualTo(4);
		assertThat(db.queryForMap("SELECT embedding_profile,embedding FROM guard_review_candidates WHERE expression=?",
				withoutExact)).containsEntry("embedding_profile", null).containsEntry("embedding", null);
		source("d-again", withoutExact + " 추가 문맥", null);
		drain(scheduled());
		assertThat(count("guard_review_candidates")).isEqualTo(4);
		assertThat(count("guard_review_findings")).isEqualTo(6);
	}
	@Test
	void concurrentAnalysisRequestsQueueOnlyOneRun() throws Exception {
		source("race", "정상 문의", null);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			var tasks = new ArrayList<Future<Boolean>>();
			for (int i = 0; i < 2; i++)
				tasks.add(pool.submit(() -> {
					gate.await();
					try {
						reviews.startAnalysis(99L);
						return true;
					} catch (com.ubot.guardreview.exception.ReviewException e) {
						return false;
					}
				}));
			gate.countDown();
			assertThat(tasks.get(0).get(10, TimeUnit.SECONDS) ^ tasks.get(1).get(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(count("guard_review_runs")).isEqualTo(1);
	}
	// These gates exist only in the disposable PostgreSQL test database, never in application code.
	void installSnapshotGate(boolean fail) {
		db.execute("""
				CREATE FUNCTION guard_review_it_snapshot_gate() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				 IF NEW.run_id IS NOT NULL THEN
				  PERFORM pg_advisory_xact_lock(674199001);
				  IF TG_ARGV[0]='fail' THEN RAISE EXCEPTION 'synthetic snapshot failure'; END IF;
				 END IF;
				 RETURN NEW;
				END $$
				""");
		db.execute("CREATE TRIGGER guard_review_it_snapshot_gate BEFORE INSERT ON guard_review_items "
				+ "FOR EACH ROW EXECUTE FUNCTION guard_review_it_snapshot_gate('" + (fail ? "fail" : "pass") + "')");
	}
	void removeSnapshotGate() {
		db.execute("DROP TRIGGER guard_review_it_snapshot_gate ON guard_review_items");
		db.execute("DROP FUNCTION guard_review_it_snapshot_gate()");
	}
	void awaitAdvisoryWait(long key) throws InterruptedException {
		long waiting = 0;
		for (int i = 0; i < 200; i++) {
			waiting = db.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' "
					+ "AND classid=0 AND objid=? AND NOT granted", Long.class, key);
			if (waiting > 0)
				break;
			Thread.sleep(25);
		}
		assertThat(waiting).as("transaction must reach the controlled database gate").isEqualTo(1);
	}
	ReviewWorkService.Proposal warningProposal() {
		return new ReviewWorkService.Proposal(
				new Suggestion(Kind.WARNING, "THREAT", "위험문장A", "테스트 경고", Stage.INPUT, "HARMFUL_REQUEST"), null);
	}
	@Test
	void manualSnapshotAllowsAlertsButPublishesRunAndItemsOnlyAfterCommit() throws Exception {
		source("snapshot-alert", "위험문장A", "정상 모델 답변");
		long previous = full();
		UUID token = UUID.randomUUID();
		assertThat(repo.claim(token)).isEqualTo(previous);
		work.finishSource(previous, token, repo.nextSource(previous), List.of(warningProposal()), true);
		work.release(previous, token);
		assertThat(count("notifications")).isZero();
		installSnapshotGate(false);
		long created;
		try (var gate = isolated.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			gate.setAutoCommit(false);
			new JdbcTemplate(new SingleConnectionDataSource(gate, true))
					.execute("SELECT pg_advisory_xact_lock(674199001)");
			var snapshot = executor.submit(() -> reviews.startAnalysis(99L));
			try {
				awaitAdvisoryWait(674199001);
				assertThat(count("guard_review_runs")).isEqualTo(1);
				assertThat(count("guard_review_items WHERE run_id IS NOT NULL")).isEqualTo(1);
				assertThat(repo.claim(UUID.randomUUID())).isNull();
				executor.submit(() -> workerContext.getBean(ReviewAlertService.class).flush()).get(5, TimeUnit.SECONDS);
				assertThat(count("notifications")).isEqualTo(2);
				executor.submit(() -> api.getBean(ReviewAlertService.class).flush()).get(5, TimeUnit.SECONDS);
				assertThat(count("notifications")).isEqualTo(2);
				var duplicate = executor.submit(() -> workerContext.getBean(ReviewService.class).startAnalysis(100L));
				awaitAdvisoryWait(674102233);
				assertThat(duplicate.isDone()).isFalse();
				gate.rollback();
				created = snapshot.get(10, TimeUnit.SECONDS);
				assertThatThrownBy(() -> duplicate.get(10, TimeUnit.SECONDS))
						.hasCauseInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
			} finally {
				gate.rollback();
			}
		} finally {
			removeSnapshotGate();
		}
		assertThat(count("guard_review_runs")).isEqualTo(2);
		assertThat(repo.run(created).total()).isEqualTo(1);
		drain(created);
		assertThat(repo.run(created).status()).isEqualTo("COMPLETED");
		assertThat(count("notifications")).isEqualTo(2);
	}
	@Test
	void snapshotDoesNotBlockWorkerCompletionOrRetention() throws Exception {
		source("snapshot-completion", "위험문장A", "동결할 모델 답변");
		long previous = full();
		UUID token = UUID.randomUUID();
		assertThat(repo.claim(token)).isEqualTo(previous);
		Source pending = repo.nextSource(previous);
		installSnapshotGate(false);
		long created;
		try (var gate = isolated.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			gate.setAutoCommit(false);
			new JdbcTemplate(new SingleConnectionDataSource(gate, true))
					.execute("SELECT pg_advisory_xact_lock(674199001)");
			// Deliberately overlap repository operations; the API normally disallows two active analyses.
			var snapshot = executor
					.submit(() -> new TransactionTemplate(api.getBean(PlatformTransactionManager.class)).execute(tx -> {
						repo.analysisEnqueueLock();
						long id = repo.createRun("ANALYSIS", "{\"analysisMode\":\"FULL\"}", 99L);
						repo.snapshotSources(id, AnalysisMode.FULL);
						return id;
					}));
			try {
				awaitAdvisoryWait(674199001);
				executor.submit(() -> {
					work.finishSource(previous, token, pending, List.of(warningProposal()), true);
					work.release(previous, token);
					workerContext.getBean(ReviewAlertService.class).flush();
				}).get(5, TimeUnit.SECONDS);
				assertThat(repo.run(previous).status()).isEqualTo("COMPLETED");
				assertThat(count("notifications")).isEqualTo(2);
				executor.submit(() -> api.getBean(ReviewRetentionService.class).cleanup()).get(5, TimeUnit.SECONDS);
				assertThat(snapshot.isDone()).isFalse();
				gate.rollback();
				created = snapshot.get(10, TimeUnit.SECONDS);
			} finally {
				gate.rollback();
			}
		} finally {
			removeSnapshotGate();
		}
		api.getBean(ReviewRetentionService.class).cleanup();
		assertThat(db.queryForMap("SELECT question_snapshot,answer_snapshot FROM guard_review_items WHERE run_id=?",
				created)).containsEntry("question_snapshot", "위험문장A").containsEntry("answer_snapshot", "동결할 모델 답변");
		drain(created);
		assertThat(repo.run(created).status()).isEqualTo("COMPLETED");
		assertThat(count("notifications")).isEqualTo(2);
	}
	@Test
	void snapshotFailureRollsBackRunAndItemsAndReleasesEnqueueLock() throws Exception {
		source("snapshot-rollback-a", "첫 원문", "첫 응답");
		source("snapshot-rollback-b", "둘째 원문", "둘째 응답");
		installSnapshotGate(true);
		try {
			assertThatThrownBy(() -> reviews.startAnalysis(99L))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(count("guard_review_runs")).isZero();
			assertThat(count("guard_review_items WHERE run_id IS NOT NULL")).isZero();
			assertThat(count("guard_review_items")).isZero();
			assertThat(repo.claim(UUID.randomUUID())).isNull();
			assertThat(MODEL.calls.get()).isZero();
		} finally {
			removeSnapshotGate();
		}
		long retried = workerContext.getBean(ReviewService.class).startAnalysis(99L);
		assertThat(repo.run(retried).total()).isEqualTo(2);
		drain(retried);
		assertThat(repo.run(retried).status()).isEqualTo("COMPLETED");
	}
	@Test
	void concurrentWorkersClaimOnlyOnceAndExpiredLeaseCannotWrite() throws Exception {
		source("claim", "후보:실험금칙표현", null);
		long run = full();
		UUID first = UUID.randomUUID(), second = UUID.randomUUID();
		UUID owner, other;
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			var a = pool.submit(() -> {
				gate.await();
				return repo.claim(first);
			});
			var b = pool.submit(() -> {
				gate.await();
				return workerContext.getBean(ReviewRepository.class).claim(second);
			});
			gate.countDown();
			Long x = a.get(10, TimeUnit.SECONDS), y = b.get(10, TimeUnit.SECONDS);
			assertThat((x == null) ^ (y == null)).isTrue();
			assertThat(x == null ? y : x).isEqualTo(run);
			owner = x == null ? second : first;
			other = x == null ? first : second;
		}
		var source = repo.nextSource(run);
		assertThatThrownBy(() -> work.finishSource(run, other, source, List.of(), true))
				.isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		db.update("UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP-interval '1 second' WHERE id=?", run);
		assertThat(repo.claim(other)).isEqualTo(run);
		assertThatThrownBy(() -> work.finishSource(run, owner, source, List.of(), true))
				.isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		work.finishSource(run, other, source, List.of(), true);
		work.release(run, other);
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
	}
	@Test
	void failedMultiCandidateApplyRollsBackWordsCandidatesAndAuditTogether() throws Exception {
		source("atomic", "후보:실험금칙표현 후보:두번째실험표현", null);
		drain(full());
		var s = selection(word("실험금칙표현"), word("두번째실험표현"));
		long v = validateSelection(s);
		drain(v);
		db.execute(
				"CREATE FUNCTION guard_review_it_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.word='두번째실험표현' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
		db.execute(
				"CREATE TRIGGER guard_review_it_fail BEFORE INSERT ON forbidden_words FOR EACH ROW EXECUTE FUNCTION guard_review_it_fail()");
		try {
			approve(v, s, 500);
			assertThat(count("forbidden_words")).isZero();
			assertThat(count("guard_review_candidates WHERE status='APPLIED'")).isZero();
			assertThat(count("guard_review_history WHERE action='APPLY'")).isZero();
		} finally {
			db.execute("DROP TRIGGER guard_review_it_fail ON forbidden_words");
			db.execute("DROP FUNCTION guard_review_it_fail()");
		}
		approve(v, s, 200);
		assertThat(count("forbidden_words")).isEqualTo(2);
	}
	@Test
	void expiredValidationCannotApplyEvenBeforeCleanupAndSnapshotsDisappearAfterCleanup() throws Exception {
		source("expiry", "후보:실험금칙표현 위험문장A", "정상 답변");
		long a = full();
		drain(a);
		var s = selection(word("실험금칙표현"));
		long v = validateSelection(s);
		drain(v);
		age(v, 31);
		age(a, 31);
		approve(v, s, 409);
		assertThat(repo.run(v).detailsRetained()).isFalse();
		var findings = request(get("/admin/guard-review/candidates/" + word("위험문장A") + "/findings"), null, 200)
				.path("data").get(0);
		assertThat(findings.path("question").isNull()).isTrue();
		api.getBean(ReviewRetentionService.class).cleanup();
		assertThat(db.queryForObject("SELECT request::text FROM guard_review_runs WHERE id=?", String.class, v))
				.contains("expired");
		assertThat(count("guard_review_items WHERE run_id IS NOT NULL AND question_snapshot IS NOT NULL")).isZero();
		assertThat(count("guard_review_findings")).isEqualTo(2);
	}
	@Test
	void retentionKeepsLatestCheckpointAndWarningDedupeWhilePruningOlderDetails() throws Exception {
		source("retention", "위험문장A", null);
		long first = full();
		drain(first);
		long second = full();
		drain(second);
		age(first, 100);
		age(second, 100);
		long c = word("위험문장A");
		db.update(
				"INSERT INTO guard_review_history(candidate_id,revision,action,admin_id,expression,created_at) VALUES(?,1,'REVIEWED',99,'위험문장A',LOCALTIMESTAMP-interval '400 days'),(?,1,'REVIEWED',99,'위험문장A',LOCALTIMESTAMP-interval '399 days')",
				c, c);
		api.getBean(ReviewRetentionService.class).cleanup();
		assertThat(count("guard_review_items WHERE run_id IS NOT NULL")).isEqualTo(1);
		assertThat(count("guard_review_findings")).isEqualTo(1);
		assertThat(count("guard_review_history")).isEqualTo(1);
		long noChange = scheduled();
		drain(noChange);
		assertThat(repo.run(noChange).total()).isZero();
		drain(full());
		assertThat(count("notifications")).isEqualTo(2);
		assertThat(count("guard_review_findings")).isEqualTo(1);
	}
	@Test
	void expiredFailedRunCannotResumeButNewIncrementalRunCanRetry() throws Exception {
		source("retry-expired", "정상 질문", null);
		MODEL.mode = "FAIL";
		long failed = full();
		drain(failed);
		age(failed, 31);
		request(post("/admin/guard-review/runs/" + failed + "/resume"), null, 409);
		api.getBean(ReviewRetentionService.class).cleanup();
		MODEL.mode = "OK";
		long retry = scheduled();
		drain(retry);
		assertThat(repo.run(retry).total()).isEqualTo(1);
		assertThat(repo.run(retry).status()).isEqualTo("COMPLETED");
	}
	@Test
	void registeredWordAndManualStatusChangesAreReflectedWithoutTakingOwnership() throws Exception {
		source("catalog", "후보:실험금칙표현", null);
		drain(full());
		long c = word("실험금칙표현");
		var created = api.getBean(ForbiddenWordService.class)
				.createForbiddenWord(new com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto("실험금칙표현"));
		assertThat(request(get("/admin/guard-review/candidates").param("kind", "WORD"), null, 200).path("data")
				.path("content").get(0).path("status").asText()).isEqualTo("REGISTERED");
		request(post("/admin/guard-review/candidates/" + c + "/decision"), Map.of("revision", 1, "action", "DISABLE"),
				409);
		assertThat(count("forbidden_words WHERE status='ACTIVE'")).isEqualTo(1);
		request(post("/admin/guard-review/validation"), Map.of("selection", selection(c)), 409);
	}
	@Test
	void candidatesBeyondFirstTwoHundredAreReachableThroughActualApi() throws Exception {
		for (int i = 0; i < 201; i++)
			db.update(
					"INSERT INTO guard_review_candidates(kind,category,expression,reason,intent,normalized_hash) VALUES('WORD','ABUSE',?,'synthetic','HARMFUL_REQUEST',?)",
					"시험표현" + i, ReviewService.normalizedHash("시험표현" + i));
		var page = request(
				get("/admin/guard-review/candidates").param("kind", "WORD").param("page", "10").param("size", "20"),
				null, 200).path("data");
		assertThat(page.path("totalElements").asInt()).isEqualTo(201);
		assertThat(page.path("content").size()).isEqualTo(1);
	}
	@Test
	void schedulerRolesDisabledFutureAndAlreadyRunningWorkAreRespected() throws Exception {
		assertThat(api.getBeansOfType(ReviewScheduler.class)).hasSize(1);
		assertThat(workerContext.getBeansOfType(ReviewScheduler.class)).isEmpty();
		api.getBean(ReviewScheduler.class).tick();
		assertThat(count("guard_review_runs")).isZero();
		request(patch("/admin/guard-review/settings"),
				Map.of("enabled", true, "intervalMinutes", 5, "groupingThreshold", 0.95, "revision", 1), 200);
		api.getBean(ReviewScheduler.class).tick();
		assertThat(count("guard_review_runs")).isZero();
		source("schedule", "새 질문", null);
		long run = scheduled();
		assertThat(repo.run(run).analysisMode()).isEqualTo("INCREMENTAL");
		api.getBean(ReviewScheduler.class).tick();
		assertThat(count("guard_review_runs")).isEqualTo(1);
		drain(run);
		api.getBean(ReviewScheduler.class).tick();
		assertThat(count("guard_review_runs")).isEqualTo(1);
	}
	@Test
	void twoAdministratorsCannotApplyTheSameTestedCandidateTwice() throws Exception {
		source("double-apply", "후보:실험금칙표현", null);
		drain(full());
		var s = selection(word("실험금칙표현"));
		long v = validateSelection(s);
		drain(v);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			var futures = new ArrayList<Future<Boolean>>();
			for (long adminId : List.of(99L, 100L))
				futures.add(pool.submit(() -> {
					gate.await();
					try {
						reviews.approveCandidates(v, s, adminId);
						return true;
					} catch (com.ubot.guardreview.exception.ReviewException e) {
						return false;
					}
				}));
			gate.countDown();
			assertThat(futures.get(0).get(10, TimeUnit.SECONDS) ^ futures.get(1).get(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(count("forbidden_words")).isEqualTo(1);
		assertThat(count("guard_review_history WHERE action='APPLY'")).isEqualTo(1);
	}
	@Test
	void invalidProposalRollsBackItemAndAllEarlierProposals() throws Exception {
		source("bad-proposal", "후보:실험금칙표현", null);
		long run = full();
		UUID token = UUID.randomUUID();
		assertThat(repo.claim(token)).isEqualTo(run);
		var source = repo.nextSource(run);
		var valid = new ReviewWorkService.Proposal(
				new Suggestion(Kind.WORD, "ABUSE", "실험금칙표현", "시험", Stage.INPUT, "HARMFUL_REQUEST"), null);
		var forged = new ReviewWorkService.Proposal(
				new Suggestion(Kind.WORD, "ABUSE", "자료에없는표현", "시험", Stage.INPUT, "HARMFUL_REQUEST"), null);
		assertThatThrownBy(() -> work.finishSource(run, token, source, List.of(valid, forged), true))
				.isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		assertThat(count("guard_review_candidates")).isZero();
		assertThat(count("guard_review_items WHERE run_id IS NOT NULL AND status='PENDING'")).isEqualTo(1);
		work.finishSource(run, token, source, List.of(valid), true);
		work.release(run, token);
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
	}
	@Test
	void databaseConstraintsRejectWarningActivationInvalidSettingsAndDuplicateWord() throws Exception {
		assertThatThrownBy(() -> db.update("UPDATE guard_review_settings SET interval_minutes=0"))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		source("constraints", "위험문장A", null);
		drain(full());
		long c = word("위험문장A");
		assertThatThrownBy(() -> db.update("UPDATE guard_review_candidates SET status='APPLIED' WHERE id=?", c))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> db.update("INSERT INTO guard_review_runs(kind,analysis_mode) VALUES('ANALYSIS',NULL)"))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void administratorValidationRunsOnNextPollBeforeRemainingAnalysisThenAnalysisContinues() throws Exception {
		// Any first item must provide the candidate; snapshot insertion order is not part of the queue contract.
		source("validation-priority-first", "후보:실험금칙표현 첫 번째 질문", "모델 응답 1");
		source("validation-priority-second", "후보:실험금칙표현 두 번째 질문", "모델 응답 2");
		source("validation-priority-third", "후보:실험금칙표현 세 번째 질문", "모델 응답 3");
		long analysis = full();
		assertThat(repo.run(analysis).total()).isEqualTo(3);
		worker.poll();
		assertThat(repo.run(analysis).status()).isEqualTo("RUNNING");
		assertThat(repo.run(analysis).processed()).isEqualTo(1);
		assertThat(MODEL.calls.get()).isEqualTo(1);

		// A real administrator request arrives while the earlier analysis still has two items.
		long validation = validateSelection(selection(word("실험금칙표현")));
		assertThat(validation).isGreaterThan(analysis);
		assertThat(repo.run(validation).status()).isEqualTo("QUEUED");
		worker.poll();

		assertThat(repo.run(validation).status()).isEqualTo("COMPLETED");
		var result = JSON.readValue(repo.run(validation).result(), Validation.class);
		assertThat(result.cases()).hasSize(5168);
		assertThat(result.passed()).isTrue();
		assertThat(repo.run(analysis).status()).isEqualTo("RUNNING");
		assertThat(repo.run(analysis).processed()).isEqualTo(1);
		assertThat(MODEL.calls.get()).isEqualTo(1);

		worker.poll();
		assertThat(repo.run(analysis).processed()).isEqualTo(2);
		assertThat(MODEL.calls.get()).isEqualTo(2);
		drain(analysis);
		assertThat(repo.run(analysis).status()).isEqualTo("COMPLETED");
		assertThat(repo.run(analysis).processed()).isEqualTo(3);
		assertThat(MODEL.calls.get()).isEqualTo(3);
	}

	@Test
	void validationPriorityPreservesFifoAndDoesNotStealAnActiveLease() {
		long analysis = repo.createRun("ANALYSIS", "{\"analysisMode\":\"FULL\"}", 99L);
		long firstValidation = repo.createRun("VALIDATION", "{}", 99L);
		long secondValidation = repo.createRun("VALIDATION", "{}", 99L);
		UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
		assertThat(repo.claim(firstOwner)).isEqualTo(firstValidation);
		assertThat(repo.claim(secondOwner)).isEqualTo(secondValidation);
		assertThat(repo.claim(UUID.randomUUID())).isEqualTo(analysis);
		assertThat(repo.claim(UUID.randomUUID())).isNull();

		// Only an expired lease can be reclaimed, preserving the original ownership protection.
		db.update("UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP-interval '1 second' WHERE id=?",
				firstValidation);
		UUID replacement = UUID.randomUUID();
		assertThat(repo.claim(replacement)).isEqualTo(firstValidation);
		assertThat(
				db.queryForObject("SELECT lease_token FROM guard_review_runs WHERE id=?", UUID.class, firstValidation))
				.isEqualTo(replacement).isNotEqualTo(firstOwner);
	}

	@Test
	void candidateApisOmitEmbeddingValuesAndKeepMetadataAndPaging() throws Exception {
		float[] values = new float[1024];
		values[0] = 1;
		var warning = new Suggestion(Kind.WARNING, "FRAUD", "위험 요청 후보", "검토 이유", Stage.INPUT, "HARMFUL_REQUEST");
		long warningId = repo.insertCandidate(warning, ReviewService.normalizedHash(warning.expression()),
				new com.ubot.guardreview.model.ReviewModels.Vector("fake-v1", values));
		assertThat(db.queryForObject("SELECT vector_dims(embedding) FROM guard_review_candidates WHERE id=?",
				Integer.class, warningId)).isEqualTo(1024);
		var page = request(get("/admin/guard-review/candidates").param("kind", "WARNING"), null, 200).path("data");
		assertThat(page.path("totalElements").asLong()).isEqualTo(1);
		assertThat(page.path("content").size()).isEqualTo(1);
		var displayed = page.path("content").get(0);
		assertThat(displayed.path("id").asLong()).isEqualTo(warningId);
		assertThat(displayed.path("kind").asString()).isEqualTo("WARNING");
		assertThat(displayed.path("expression").asString()).isEqualTo(warning.expression());
		assertThat(displayed.path("reason").asString()).isEqualTo(warning.reason());
		assertThat(displayed.path("profile").asString()).isEqualTo("fake-v1");
		assertThat(displayed.path("revision").asLong()).isEqualTo(1);
		assertThat(displayed.path("occurrences").asLong()).isZero();
		assertThat(displayed.has("vector")).isFalse();
		assertThat(displayed.has("embedding")).isFalse();

		var word = new Suggestion(Kind.WORD, "ABUSE", "후보표현", "검토 이유", Stage.OUTPUT, "HARMFUL_OUTPUT");
		long wordId = repo.insertCandidate(word, ReviewService.normalizedHash(word.expression()), null);
		var wordPage = request(get("/admin/guard-review/candidates").param("kind", "WORD"), null, 200).path("data");
		assertThat(wordPage.path("content").get(0).has("vector")).isFalse();
		var edited = request(patch("/admin/guard-review/candidates/{id}", wordId),
				Map.of("revision", 1, "expression", "수정후보표현", "reason", "수정 이유"), 200).path("data");
		assertThat(edited.path("id").asLong()).isEqualTo(wordId);
		assertThat(edited.path("expression").asString()).isEqualTo("수정후보표현");
		assertThat(edited.path("revision").asLong()).isEqualTo(2);
		assertThat(edited.has("vector")).isFalse();
	}

	@Test
	void candidateReadsSucceedWithoutPermissionToReadEmbeddingColumn() throws Exception {
		float[] values = new float[1024];
		values[0] = 1;
		var warning = new Suggestion(Kind.WARNING, "FRAUD", "목록 검증 후보", "검토 이유", Stage.INPUT, "HARMFUL_REQUEST");
		long id = repo.insertCandidate(warning, ReviewService.normalizedHash(warning.expression()),
				new com.ubot.guardreview.model.ReviewModels.Vector("fake-v1", values));
		try (var connection = isolated.getConnection()) {
			connection.setAutoCommit(false);
			try {
				var restricted = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
				restricted.execute("CREATE ROLE guard_review_candidate_reader");
				restricted.execute(
						"""
								GRANT SELECT(id,kind,category,expression,reason,intent,revision,status,word_id,embedding_profile)
								ON guard_review_candidates TO guard_review_candidate_reader
								""");
				restricted.execute(
						"GRANT SELECT(candidate_id,notified_at) ON guard_review_findings TO guard_review_candidate_reader");
				restricted.execute("SET LOCAL ROLE guard_review_candidate_reader");
				var reader = new ReviewRepository(restricted, new ForbiddenWordCatalogLock(restricted));
				Candidate candidate = reader.candidate(id, false);
				assertThat(candidate.expression()).isEqualTo(warning.expression());
				assertThat(candidate.profile()).isEqualTo("fake-v1");
				var page = reader.getCandidatePage("WARNING", 0, 10);
				assertThat(page.totalElements()).isEqualTo(1);
				assertThat(page.content()).containsExactly(candidate);
				try (var statement = connection.createStatement()) {
					assertThatThrownBy(() -> statement.executeQuery("SELECT embedding FROM guard_review_candidates"))
							.isInstanceOf(java.sql.SQLException.class)
							.satisfies(e -> assertThat(((java.sql.SQLException) e).getSQLState()).isEqualTo("42501"));
				}
			} finally {
				// The temporary role and grants exist only in this isolated test transaction.
				connection.rollback();
			}
		}
	}

	@Test
	void analysisFailureKeepsActualProgressAndPendingItemsInsteadOfReportingOneHundredPercent() throws Exception {
		for (int i = 0; i < 3; i++)
			source("progress-" + i, "정상 질문 " + i, "정상 응답 " + i);
		long run = full();
		UUID token = UUID.randomUUID();
		assertThat(repo.claim(token)).isEqualTo(run);
		work.finishSource(run, token, repo.nextSource(run), List.of(), true);
		work.failAnalysis(run, token);
		assertThat(repo.run(run).status()).isEqualTo("FAILED");
		assertThat(repo.run(run).processed()).isEqualTo(1);
		assertThat(repo.run(run).total()).isEqualTo(3);
		var result = JSON.readTree(repo.run(run).result());
		assertThat(result.path("completed").asInt()).isEqualTo(1);
		assertThat(result.path("failed").asInt()).isZero();
		assertThat(result.path("pending").asInt()).isEqualTo(2);
		assertThat(result.path("error").asText()).isEqualTo("REVIEW_FAILED");
		assertThat(count("guard_review_items WHERE run_id=" + run + " AND status='PENDING'")).isEqualTo(2);
		assertThatThrownBy(() -> work.failAnalysis(run, token))
				.isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		reviews.resumeAnalysis(run);
		assertThat(repo.run(run).processed()).isEqualTo(1);
		drain(run);
		assertThat(repo.run(run).processed()).isEqualTo(3);
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
	}
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void modelOutageRetriesSamePendingItemWithDurableDelayWithoutFailingOtherItems(boolean recovery) throws Exception {
		for (int i = 0; i < 3; i++)
			source("retry-" + i, "후보:실험금칙표현 질문 " + i, "모델 응답 " + i);
		long run = full();
		MODEL.mode = "UNAVAILABLE";
		long failedItem = 0;
		for (int failure = 1; failure <= (recovery ? 2 : 3); failure++) {
			if (failure > 1)
				db.update("UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP-interval '1 second' WHERE id=?",
						run);
			worker.poll();
			var result = JSON.readTree(repo.run(run).result());
			assertThat(result.path("consecutiveFailures").asInt()).isEqualTo(failure);
			if (failure == 1)
				failedItem = result.path("lastSourceId").asLong();
			assertThat(result.path("lastSourceId").asLong()).isEqualTo(failedItem);
			assertThat(result.path("errorCode").asText()).isEqualTo("LLM-003");
			assertThat(MODEL.calls.get()).isEqualTo(failure);
			if (failure < 3) {
				assertThat(repo.run(run).status()).isEqualTo("RUNNING");
				assertThat(repo.run(run).processed()).isZero();
				assertThat(count("guard_review_items WHERE run_id=" + run + " AND status='PENDING'")).isEqualTo(3);
				double remaining = db.queryForObject(
						"SELECT extract(epoch FROM lease_until-LOCALTIMESTAMP) FROM guard_review_runs WHERE id=?",
						Double.class, run);
				assertThat(remaining).isBetween(failure == 1 ? 7.0 : 27.0, failure == 1 ? 10.1 : 30.1);
				assertThat(result.has("retryAt")).isTrue();
				worker.poll();
				assertThat(MODEL.calls.get()).isEqualTo(failure);
			}
		}
		if (!recovery) {
			assertThat(repo.run(run).status()).isEqualTo("FAILED");
			assertThat(repo.run(run).processed()).isEqualTo(1);
			assertThat(repo.run(run).total()).isEqualTo(3);
			assertThat(count("guard_review_items WHERE run_id=" + run + " AND status='FAILED'")).isEqualTo(1);
			assertThat(count("guard_review_items WHERE run_id=" + run + " AND status='PENDING'")).isEqualTo(2);
			worker.poll();
			assertThat(MODEL.calls.get()).isEqualTo(3);
			reviews.resumeAnalysis(run);
			assertThat(repo.run(run).processed()).isZero();
			assertThat(JSON.readTree(repo.run(run).result()).has("consecutiveFailures")).isFalse();
		} else {
			var suggestion = new Suggestion(Kind.WORD, "ABUSE", "실험금칙표현", "테스트", Stage.INPUT, "HARMFUL_REQUEST");
			long candidate = repo.insertCandidate(suggestion, ReviewService.normalizedHash(suggestion.expression()),
					null);
			long validation = validateSelection(selection(candidate));
			worker.poll();
			assertThat(repo.run(validation).status()).isEqualTo("COMPLETED");
			assertThat(MODEL.calls.get()).isEqualTo(2);
			db.update("UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP-interval '1 second' WHERE id=?", run);
		}
		MODEL.mode = "OK";
		drain(run);
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
		assertThat(repo.run(run).processed()).isEqualTo(3);
		assertThat(count("guard_review_items WHERE run_id=" + run + " AND status='FAILED'")).isZero();
		assertThat(JSON.readTree(repo.run(run).result()).has("consecutiveFailures")).isFalse();
		assertThat(repo.run(scheduled()).total()).isZero();
	}
	@Test
	void leaseRenewalCannotReviveExpiredOrReplacedOwnerAndFailedValidationDoesNotForceProgress() {
		long run = repo.createRun("VALIDATION", "{}", 99L);
		repo.total(run, 5168);
		UUID old = UUID.randomUUID(), replacement = UUID.randomUUID();
		assertThat(repo.claim(old)).isEqualTo(run);
		db.update("UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP-interval '1 second' WHERE id=?", run);
		assertThat(repo.renewLease(run, old)).isFalse();
		assertThat(repo.claim(replacement)).isEqualTo(run);
		assertThat(repo.renewLease(run, old)).isFalse();
		assertThatThrownBy(() -> work.finishValidation(run, old, "{}", true))
				.isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		assertThat(repo.renewLease(run, replacement)).isTrue();
		work.finishValidation(run, replacement, "{\"error\":\"REVIEW_FAILED\"}", false);
		assertThat(repo.run(run).processed()).isZero();
		assertThat(repo.run(run).total()).isEqualTo(5168);
		assertThat(repo.run(run).status()).isEqualTo("FAILED");
		assertThat(repo.renewLease(run, replacement)).isFalse();
	}
	@Test
	void dedicatedHeartbeatRenewsWhileValidationThreadIsBlockedAndPreventsDuplicateClaim() throws Exception {
		source("heartbeat-validation", "후보:실험금칙표현", "정상 모델 응답");
		drain(full());
		long run = validateSelection(selection(word("실험금칙표현")));
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var slow = org.mockito.Mockito.mock(ReviewValidationService.class);
		org.mockito.Mockito.when(slow.dataset()).thenReturn(validator.dataset());
		org.mockito.Mockito.when(slow.datasetVersion()).thenReturn(validator.datasetVersion());
		org.mockito.Mockito.when(slow.baseVersion(org.mockito.ArgumentMatchers.anySet()))
				.thenAnswer(invocation -> validator.baseVersion(invocation.getArgument(0)));
		org.mockito.Mockito.when(slow.validate(org.mockito.ArgumentMatchers.anyCollection(),
				org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Runnable.class)))
				.thenAnswer(invocation -> {
					db.update(
							"UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP+interval '45 seconds' WHERE id=?",
							run);
					entered.countDown();
					assertThat(release.await(50, TimeUnit.SECONDS)).isTrue();
					return validator.validate(invocation.getArgument(0), invocation.getArgument(1),
							invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4));
				});
		var active = new ReviewWorker(repo, workerContext.getBean(ReviewAnalyzer.class),
				workerContext.getBean(ReviewEmbedding.class), reviews, work, slow,
				workerContext.getBean(ReviewAlertService.class), workerContext.getBean(ReviewLeaseService.class));
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var task = executor.submit(active::poll);
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				boolean renewed = false;
				for (int i = 0; i < 400; i++) {
					renewed = Boolean.TRUE.equals(db.queryForObject(
							"SELECT lease_until>LOCALTIMESTAMP+interval '4 minutes' FROM guard_review_runs WHERE id=?",
							Boolean.class, run));
					if (renewed)
						break;
					Thread.sleep(100);
				}
				assertThat(renewed).as("dedicated timer must renew while the validation thread is blocked").isTrue();
				assertThat(task.isDone()).isFalse();
				assertThat(workerContext.getBean(ReviewRepository.class).claim(UUID.randomUUID())).isNull();
				release.countDown();
				task.get(20, TimeUnit.SECONDS);
			} finally {
				release.countDown();
			}
		}
		assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
		assertThat(repo.run(run).processed()).isEqualTo(5168);
		assertThat(JSON.readValue(repo.run(run).result(), Validation.class).cases()).hasSize(5168);
		assertThat(MODEL.calls.get()).isEqualTo(1);
	}
	@Test
	void busyForegroundDefersAnalysisWithoutFailedItemOrCheckpointThenResumes() throws Exception {
		source("capacity-defer", "정상 질문", "정상 모델 응답");
		long run = full();
		var foregroundStarted = new CountDownLatch(1);
		var releaseForeground = new CountDownLatch(1);
		var analysisCalls = new AtomicInteger();
		var client = org.mockito.Mockito.mock(LlmClient.class);
		org.mockito.Mockito.when(client.generateAnswer(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
			LlmRequestDto request = invocation.getArgument(0);
			if (request.hasResponseSchema()) {
				analysisCalls.incrementAndGet();
				return new LlmResponseDto("{\"suggestions\":[]}");
			}
			foregroundStarted.countDown();
			assertThat(releaseForeground.await(10, TimeUnit.SECONDS)).isTrue();
			return new LlmResponseDto("가짜 채팅 답변");
		});
		var shared = new LlmService(client, 2);
		var background = new ReviewWorker(repo, new LlmReviewAnalyzer(shared),
				workerContext.getBean(ReviewEmbedding.class), reviews, work, validator,
				workerContext.getBean(ReviewAlertService.class), workerContext.getBean(ReviewLeaseService.class));
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var foreground = executor.submit(() -> shared.generateAnswer(new LlmRequestDto(
					List.of(new com.ubot.llm.dto.request.LlmMessageRequestDto(com.ubot.llm.enums.LlmMessageRole.USER,
							"가짜 채팅 질문")))));
			try {
				assertThat(foregroundStarted.await(5, TimeUnit.SECONDS)).isTrue();
				background.poll();
				assertThat(analysisCalls.get()).isZero();
				assertThat(repo.run(run).status()).isEqualTo("RUNNING");
				assertThat(repo.run(run).processed()).isZero();
				assertThat(db.queryForMap("SELECT status,error_code FROM guard_review_items WHERE run_id=?", run))
						.containsEntry("status", "PENDING").containsEntry("error_code", null);
				assertThat(db.queryForObject("SELECT lease_token FROM guard_review_runs WHERE id=?", UUID.class, run))
						.isNull();
				assertThat(count("guard_review_items WHERE run_id IS NOT NULL AND status='COMPLETED'")).isZero();
				releaseForeground.countDown();
				assertThat(foreground.get(5, TimeUnit.SECONDS).answer()).isEqualTo("가짜 채팅 답변");
				background.poll();
				assertThat(analysisCalls.get()).isEqualTo(1);
				assertThat(repo.run(run).status()).isEqualTo("COMPLETED");
				assertThat(repo.run(run).processed()).isEqualTo(1);
				long incremental = scheduled();
				drain(incremental);
				assertThat(repo.run(incremental).total()).isZero();
			} finally {
				releaseForeground.countDown();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void manualChangesAndReviewApprovalShareLockAcrossContextsUntilCommitOrRollback(boolean rollback) throws Exception {
		source("catalog-lock", "후보:실험금칙표현", null);
		drain(full());
		var selected = selection(word("실험금칙표현"));
		long validation = validateSelection(selected);
		drain(validation);
		var held = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var manual = executor.submit(() -> new TransactionTemplate(api.getBean(PlatformTransactionManager.class))
					.executeWithoutResult(tx -> {
						api.getBean(ForbiddenWordService.class).createForbiddenWord(
								new com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto("수동잠금검증표현"));
						held.countDown();
						try {
							assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new IllegalStateException(e);
						}
						if (rollback)
							tx.setRollbackOnly();
					}));
			try {
				assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
				var approval = executor.submit(() -> {
					workerContext.getBean(ReviewService.class).approveCandidates(validation, selected, 99);
					return null;
				});
				long waiting = 0;
				for (int i = 0; i < 200; i++) {
					waiting = db.queryForObject("""
							SELECT count(*) FROM pg_locks WHERE locktype='advisory'
							AND classid=0 AND objid=674102231 AND NOT granted
							""", Long.class);
					if (waiting > 0)
						break;
					Thread.sleep(25);
				}
				assertThat(waiting).as("review approval must wait on the manual catalog transaction").isEqualTo(1);
				assertThat(approval.isDone()).isFalse();
				release.countDown();
				manual.get(10, TimeUnit.SECONDS);
				if (rollback) {
					approval.get(10, TimeUnit.SECONDS);
					assertThat(count("forbidden_words WHERE word='실험금칙표현'")).isEqualTo(1);
					assertThat(count("forbidden_words WHERE word='수동잠금검증표현'")).isZero();
				} else {
					assertThatThrownBy(() -> approval.get(10, TimeUnit.SECONDS))
							.hasCauseInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
					assertThat(count("forbidden_words WHERE word='실험금칙표현'")).isZero();
					assertThat(count("forbidden_words WHERE word='수동잠금검증표현'")).isEqualTo(1);
				}
			} finally {
				release.countDown();
			}
		}
	}

	@Test
	void sourceKeysAreHashedAndReanalysisKeepsCheckpointAndWarningDedupe() throws Exception {
		source("private-request-key", "위험문장A", "정상 답변");
		long run = full();
		drain(run);
		String expected = sourceKeyHash("none:1:private-request-key");
		assertThat(db.queryForObject("SELECT source_key FROM guard_review_items WHERE run_id=?", String.class, run))
				.isEqualTo(expected);
		assertThat(expected).matches("^sha256:[0-9a-f]{64}$").doesNotContain("private-request-key");
		assertThat(db.queryForObject("SELECT source_key FROM guard_review_findings", String.class)).isEqualTo(expected);
		int findings = count("guard_review_findings"), notifications = count("notifications");
		long next = full();
		drain(next);
		assertThat(count("guard_review_findings")).isEqualTo(findings);
		assertThat(count("notifications")).isEqualTo(notifications);
		long incremental = scheduled();
		assertThat(repo.run(incremental).total()).isZero();
		drain(incremental);
		long candidate = word("위험문장A");
		var details = request(get("/admin/guard-review/candidates/" + candidate + "/findings"), null, 200);
		assertThat(details.toString()).contains(expected).doesNotContain("private-request-key");
	}

	@Test
	void validationRunApiUsesObjectSummaryAndPagesStoredCases() throws Exception {
		var cases = new ArrayList<CaseResult>();
		for (int i = 0; i < 23; i++)
			cases.add(new CaseResult("case-" + i, "NORMAL", Stage.INPUT, "before-" + i, "after-" + i, false, false,
					false, false));
		var result = new Validation("rules", "dataset", cases, 0, 0, 0, 0);
		long id = db.queryForObject("""
				INSERT INTO guard_review_runs(kind,status,total,processed,result,finished_at)
				VALUES('VALIDATION','COMPLETED',23,23,?::jsonb,LOCALTIMESTAMP) RETURNING id
				""", Long.class, JSON.writeValueAsString(result));
		var summary = request(get("/admin/guard-review/runs/" + id), null, 200).path("data");
		assertThat(summary.path("result").isObject()).isTrue();
		assertThat(summary.path("result").has("cases")).isFalse();
		assertThat(summary.path("result").path("caseCount").asInt()).isEqualTo(23);
		assertThat(summary.has("request")).isFalse();
		assertThat(summary.has("leaseToken")).isFalse();
		for (int page = 0; page < 4; page++) {
			var response = request(get("/admin/guard-review/runs/" + id + "/cases").param("page", String.valueOf(page))
					.param("size", "10"), null, 200).path("data");
			int expected = Math.min(10, Math.max(0, 23 - page * 10));
			assertThat(response.path("content").size()).isEqualTo(expected);
			assertThat(response.path("totalElements").asInt()).isEqualTo(23);
			assertThat(response.path("totalPages").asInt()).isEqualTo(3);
			for (int index = 0; index < expected; index++)
				assertThat(response.path("content").get(index).path("id").asText())
						.isEqualTo("case-" + (page * 10 + index));
		}
		request(get("/admin/guard-review/runs/" + id + "/cases").param("size", "11"), null, 400);
		request(get("/admin/guard-review/runs/" + id + "/cases").param("page", "-1"), null, 400);
		request(get("/admin/guard-review/runs/" + id + "/cases"), null, 403, normal);
		db.update("UPDATE guard_review_runs SET finished_at=LOCALTIMESTAMP-interval '31 days' WHERE id=?", id);
		request(get("/admin/guard-review/runs/" + id + "/cases"), null, 409);
	}
	@Test
	void validationCaseApiRejectsWrongKindAndInProgressOrMissingRuns() throws Exception {
		long analysis = repo.createRun("ANALYSIS", "{\"analysisMode\":\"FULL\"}", 99L);
		request(get("/admin/guard-review/runs/" + analysis + "/cases"), null, 400);
		long validation = repo.createRun("VALIDATION", "{}", 99L);
		request(get("/admin/guard-review/runs/" + validation + "/cases"), null, 409);
		request(get("/admin/guard-review/runs/999999999/cases"), null, 404);
	}
}
