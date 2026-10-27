package com.ubot.counsel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class CounselHistoryMigrationTest {
	@Test
	void createsCounselSchemaAndFaqSelectionWithOneMigrationFromV38() {
		try (var database = database()) {
			database.start();
			var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
			Flyway.configure().dataSource(dataSource).target("38").load().migrate();
			var jdbc = new JdbcTemplate(dataSource);
			assertThat(jdbc.queryForObject("SELECT to_regclass('counseling.sessions')", String.class)).isNull();
			assertThat(jdbc.queryForObject("SELECT to_regclass('public.conversation_faq_selections')", String.class)).isNull();
			long customer = createUser(jdbc, "customer", "USER");
			long conversation = jdbc.queryForObject("INSERT INTO conversations(public_id,user_id,type) VALUES (?,?,'MEMBER') RETURNING conversation_id",
					Long.class, UUID.randomUUID(), customer);
			var before = jdbc.queryForList("SELECT * FROM conversations");

			var flyway = Flyway.configure().dataSource(dataSource).target("39").load();
			assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
			flyway.validate();
			assertThat(jdbc.queryForList("SELECT * FROM conversations")).isEqualTo(before);
			// FAQ 선택은 같은 ID로 두 번 저장되지 않고 대화와 함께 삭제됨
			UUID selection = UUID.randomUUID();
			jdbc.update("INSERT INTO conversation_faq_selections(selection_id,conversation_id,faq_id,question,answer) VALUES (?,?,999,'질문 원문','답변 원문')",
					selection, conversation);
			assertThatThrownBy(() -> jdbc.update("INSERT INTO conversation_faq_selections(selection_id,conversation_id,faq_id,question,answer) VALUES (?,?,999,'중복','중복')",
					selection, conversation)).isInstanceOf(DataIntegrityViolationException.class);
			jdbc.update("DELETE FROM conversations WHERE conversation_id=?", conversation);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_faq_selections", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					WHERE table_schema='counseling' AND table_name='sessions' AND column_name='conversation_id'
					""", String.class)).isEqualTo("NO");
			assertThat(jdbc.queryForList("""
					SELECT column_name FROM information_schema.columns
					WHERE table_schema='counseling' AND table_name='session_history' AND column_name LIKE 'origin_%'
					ORDER BY column_name
					""", String.class)).containsExactly("origin_sequence", "origin_session_id");
			assertThat(jdbc.queryForObject("""
					SELECT confdeltype::text FROM pg_constraint
					WHERE conrelid='counseling.sessions'::regclass AND conname='fk_sessions_conversation'
					""", String.class)).isEqualTo("c");
			assertThat(jdbc.queryForList("""
					SELECT version FROM flyway_schema_history WHERE version::integer > 38 ORDER BY installed_rank
					""", String.class)).containsExactly("39");
			assertThat(flyway.migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void preservesMessagesAndAuditWhenCounselorIsDeletedAndCascadesCustomerDeletion() {
		try (var database = database()) {
			database.start();
			var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
			Flyway.configure().dataSource(dataSource).load().migrate();
			var jdbc = new JdbcTemplate(dataSource);
			long customer = createUser(jdbc, "customer", "USER");
			long counselor = createUser(jdbc, "counselor", "COUNSELOR");
			UUID conversation = UUID.randomUUID();
			jdbc.update("INSERT INTO conversations(public_id,user_id,type) VALUES (?,?,'MEMBER')", conversation, customer);
			UUID session = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO counseling.sessions(id,customer_id,counselor_id,status,conversation_id,ended_at)
					VALUES (?,?,?,'ENDED',?,now())
					""", session, customer, counselor, conversation);
			long message = jdbc.queryForObject("""
					INSERT INTO counseling.messages(session_id,sender_id,sender_role,assignment_version,client_message_id,content,status,delivery_sequence,decided_at)
					VALUES (?,?,'COUNSELOR',0,?,'보존할 상담 답변','ALLOWED',1,now()) RETURNING id
					""", Long.class, session, counselor, UUID.randomUUID());
			jdbc.update("""
					INSERT INTO counseling.session_history(session_id,sequence,role,content,source,origin_session_id,origin_sequence)
					VALUES (?,1,'AI','이전 AI 답변','SAVED',?,1)
					""", session, session);
			jdbc.update("""
					INSERT INTO counseling.safety_filter_events(message_id,decision,threshold,model_name,profile_version,rule_version)
					VALUES (?,'ALLOWED',0.95,'ubot-embedding','test','v1')
					""", message);
			jdbc.update("""
					INSERT INTO counseling.role_audit(admin_id,user_id,previous_role,new_role)
					VALUES (?,?,'USER','COUNSELOR')
					""", counselor, counselor);
			jdbc.update("INSERT INTO counseling.counselor_presence(user_id,status,last_heartbeat_at) VALUES (?,'OFFLINE',now())", counselor);
			jdbc.update("INSERT INTO counseling.rate_buckets(user_id) VALUES (?)", counselor);

			jdbc.update("DELETE FROM users WHERE user_id=?", counselor);
			assertThat(jdbc.queryForObject("SELECT counselor_id FROM counseling.sessions WHERE id=?", Long.class, session)).isNull();
			assertThat(jdbc.queryForObject("SELECT sender_id FROM counseling.messages WHERE id=?", Long.class, message)).isNull();
			assertThat(jdbc.queryForObject("SELECT content FROM counseling.messages WHERE id=?", String.class, message)).isEqualTo("보존할 상담 답변");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.session_history", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_events", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.role_audit WHERE admin_id IS NULL AND user_id IS NULL", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.counselor_presence", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.rate_buckets", Integer.class)).isZero();

			jdbc.update("DELETE FROM users WHERE user_id=?", customer);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.session_history", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_events", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.role_audit", Integer.class)).isEqualTo(1);
		}
	}

	private long createUser(JdbcTemplate jdbc, String name, String role) {
		return jdbc.queryForObject("""
				INSERT INTO users(email,password_hash,name,role) VALUES (?,'fixture',?,?) RETURNING user_id
				""", Long.class, name + "@example.test", name, role);
	}

	private PostgreSQLContainer database() {
		var image = DockerImageName.parse("ubot-postgres:18-pgvector0.8.6-postgis3.6.4").asCompatibleSubstituteFor("postgres");
		return new PostgreSQLContainer(image).withDatabaseName("counsel_migration")
				.withUsername("migration_test").withPassword(UUID.randomUUID().toString()).withReuse(false);
	}
}
