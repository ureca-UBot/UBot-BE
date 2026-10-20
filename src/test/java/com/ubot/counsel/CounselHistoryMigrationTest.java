package com.ubot.counsel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class CounselHistoryMigrationTest {
	@Test
	void createsFinalCounselSchemaWithOneMigrationFromV38() {
		try (var database = database()) {
			database.start();
			var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
			Flyway.configure().dataSource(dataSource).target("38").load().migrate();
			var jdbc = new JdbcTemplate(dataSource);
			assertThat(jdbc.queryForObject("SELECT to_regclass('counseling.sessions')", String.class)).isNull();

			var flyway = Flyway.configure().dataSource(dataSource).load();
			assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
			flyway.validate();
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
	void adoptsStandaloneV4AndPreservesHistoryVectorsAndAuditInOneMigration() throws Exception {
		try (var database = database()) {
			database.start();
			var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
			Flyway.configure().dataSource(dataSource).target("38").load().migrate();
			var jdbc = new JdbcTemplate(dataSource);
			var legacy = new ClassPathResource("fixtures/counsel-v4.sql");
			jdbc.execute(new String(legacy.getContentAsByteArray(), StandardCharsets.UTF_8));
			long customer = jdbc.queryForObject("""
					INSERT INTO users(email,password_hash,name,role) VALUES ('migration@example.test','fixture','fixture','USER')
					RETURNING user_id
					""", Long.class);
			UUID conversation = UUID.randomUUID();
			jdbc.update("INSERT INTO conversations(public_id,user_id,type) VALUES (?,?,'MEMBER')", conversation, customer);
			UUID first = UUID.randomUUID();
			UUID second = UUID.randomUUID();
			UUID unlinked = UUID.randomUUID();
			UUID orphan = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO counseling.sessions(id,customer_id,status,conversation_id,created_at,ended_at)
					VALUES (?,?,'ENDED',?,now()-interval '2 days',now()-interval '1 day'),
					       (?,?,'ENDED',?,now()-interval '12 hours',now()),
					       (?,?,'ENDED',NULL,now(),now()),
					       (?,?,'ENDED',?,now(),now())
					""", first, customer, conversation, second, customer, conversation,
					unlinked, customer, orphan, customer, UUID.randomUUID());
			for (UUID session : new UUID[]{first, second}) {
				jdbc.update("""
						INSERT INTO counseling.session_history(session_id,sequence,role,content,source)
						VALUES (?,1,'USER','같은 질문','SCREEN'),(?,2,'USER','같은 질문','SCREEN')
						""", session, session);
			}
			jdbc.update("""
					INSERT INTO counseling.session_history(session_id,sequence,role,content,source)
					VALUES (?,1,'AI','원본 대화가 없어진 AI 답변','SAVED'),(?,2,'COUNSELOR','이전 상담 인계 기록','COUNSEL')
					""", orphan, orphan);
			long messageId = jdbc.queryForObject("""
					INSERT INTO counseling.messages(session_id,sender_id,sender_role,assignment_version,client_message_id,content,status,delivery_sequence,decided_at)
					VALUES (?,?,'USER',0,?,'기존 상담 메시지','ALLOWED',1,now())
					RETURNING id
					""", Long.class, unlinked, customer, UUID.randomUUID());
			jdbc.execute("""
					INSERT INTO counseling.guard_profiles(profile_version,model_name,preprocessing_version,dataset_sha256)
					VALUES ('legacy-v4','ubot-embedding','nfkc-space-v1',repeat('a',64));
					INSERT INTO counseling.safety_filter_vectors(id,category,example_text,embedding,severity,model_name,profile_version)
					VALUES (41,'INSULT','보존할 금지 문장',array_fill(1::real,ARRAY[1024])::vector,1,'ubot-embedding','legacy-v4');
					INSERT INTO counseling.safety_sentences(id,profile_version,model_name,content,normalized_sha256,severity,indexed_revision,indexed_vector_id)
					VALUES (51,'legacy-v4','ubot-embedding','보존할 금지 문장',repeat('b',64),1,1,41);
					INSERT INTO counseling.safety_sentence_audit(sentence_id,action,revision,content,severity,enabled,vector_id)
					VALUES (51,'SEED',1,'보존할 금지 문장',1,true,41);
					""");
			jdbc.update("""
					INSERT INTO counseling.safety_filter_events(message_id,decision,matched_vector_id,score,threshold,model_name,profile_version,rule_version)
					VALUES (?,'ALLOWED',41,0.1,0.95,'ubot-embedding','legacy-v4','v1')
					""", messageId);
			var vectorsBefore = jdbc.queryForList("SELECT id,embedding::text,active FROM counseling.safety_filter_vectors");
			var sentencesBefore = jdbc.queryForList("SELECT * FROM counseling.safety_sentences");
			var auditBefore = jdbc.queryForList("SELECT * FROM counseling.safety_sentence_audit");

			var flyway = Flyway.configure().dataSource(dataSource).load();
			assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
			flyway.validate();
			assertThat(jdbc.queryForList("SELECT id,embedding::text,active FROM counseling.safety_filter_vectors")).isEqualTo(vectorsBefore);
			assertThat(jdbc.queryForList("SELECT * FROM counseling.safety_sentences")).isEqualTo(sentencesBefore);
			assertThat(jdbc.queryForList("SELECT * FROM counseling.safety_sentence_audit")).isEqualTo(auditBefore);
			assertThat(jdbc.queryForObject("SELECT matched_vector_id FROM counseling.safety_filter_events WHERE message_id=?",
					Long.class, messageId)).isEqualTo(41L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions WHERE conversation_id IS NULL", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE user_id=?", Integer.class, customer)).isEqualTo(3);
			assertThat(jdbc.queryForList("SELECT origin_session_id FROM counseling.session_history WHERE session_id=? ORDER BY sequence",
					UUID.class, second)).containsExactly(first, first);
			assertThat(jdbc.queryForList("SELECT origin_sequence FROM counseling.session_history WHERE session_id=? ORDER BY sequence",
					Long.class, second)).containsExactly(1L, 2L);
			assertThat(jdbc.queryForObject("SELECT content FROM counseling.messages WHERE session_id=?", String.class, unlinked))
					.isEqualTo("기존 상담 메시지");
			UUID recovered = jdbc.queryForObject("SELECT conversation_id FROM counseling.sessions WHERE id=?", UUID.class, orphan);
			var timeline = new com.ubot.counsel.history.repository.ConversationTimelineRepository(jdbc);
			assertThat(timeline.findTimeline(recovered, 0, 100).stream().map(item -> item.content()).toList())
					.contains("원본 대화가 없어진 AI 답변", "이전 상담 인계 기록");
			jdbc.update("DELETE FROM users WHERE user_id=?", customer);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.sessions", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.messages", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM counseling.safety_filter_events", Integer.class)).isZero();
		}
	}

	private PostgreSQLContainer database() {
		var image = DockerImageName.parse("ubot-postgres:18-pgvector0.8.6-postgis3.6.4").asCompatibleSubstituteFor("postgres");
		return new PostgreSQLContainer(image).withDatabaseName("counsel_migration")
				.withUsername("migration_test").withPassword(UUID.randomUUID().toString()).withReuse(false);
	}
}
