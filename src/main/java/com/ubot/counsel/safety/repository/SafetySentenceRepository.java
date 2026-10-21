package com.ubot.counsel.safety.repository;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.entity.SafetySentence;
import com.ubot.counsel.safety.util.SafetyTextNormalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SafetySentenceRepository {
	private static final String SELECT = """
			SELECT *, (index_token IS NOT NULL AND index_started_at > now() - interval '30 seconds') AS index_in_progress
			FROM counseling.safety_sentences
			""";
	private final JdbcTemplate jdbc;
	private final CounselProperties properties;
	private SafetySentence map(ResultSet r, int row) throws SQLException {
		return new SafetySentence(r.getLong("id"), r.getString("content"), r.getInt("severity"), r.getBoolean("enabled"),
				r.getLong("revision"), r.getObject("indexed_revision", Long.class), r.getObject("indexed_vector_id", Long.class),
				r.getObject("index_token", UUID.class), r.getBoolean("index_in_progress"), r.getString("index_error"),
				r.getString("model_name"), r.getString("profile_version"), r.getTimestamp("created_at").toInstant(),
				r.getTimestamp("updated_at").toInstant());
	}
	public SafetySentence get(long id, boolean lock) {
		var rows = jdbc.query(SELECT + """
				WHERE id=? AND profile_version=? AND model_name=? AND deleted_at IS NULL
				""" + (lock ? " FOR UPDATE" : ""), this::map, id, properties.guard().profile(), properties.guard().model());
		return rows.isEmpty() ? null : rows.getFirst();
	}
	public List<SafetySentence> list(String keyword, int page) {
		return jdbc.query(SELECT + """
				WHERE profile_version=? AND model_name=? AND deleted_at IS NULL AND strpos(content, ?) > 0
				ORDER BY id DESC LIMIT 51 OFFSET ?
				""", this::map, properties.guard().profile(), properties.guard().model(), keyword, page * 50);
	}
	public boolean profileReady() {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS(SELECT 1 FROM counseling.guard_profiles
				WHERE profile_version=? AND model_name=? AND preprocessing_version=?)
				""", Boolean.class, properties.guard().profile(), properties.guard().model(), SafetyTextNormalizer.PREPROCESSING));
	}
	public List<SafetySentence> pendingIndex() {
		return jdbc.query(SELECT + """
				WHERE profile_version=? AND model_name=? AND deleted_at IS NULL AND enabled
				AND indexed_revision IS DISTINCT FROM revision
				AND (index_token IS NULL OR index_started_at <= now() - interval '30 seconds')
				AND (index_error IS NULL OR updated_at <= now() - interval '30 seconds')
				ORDER BY updated_at,id LIMIT 1
				""", this::map, properties.guard().profile(), properties.guard().model());
	}
	public long insert(String content, String hash, int severity) {
		return jdbc.queryForObject("""
				INSERT INTO counseling.safety_sentences(profile_version,model_name,content,normalized_sha256,severity)
				VALUES (?,?,?,?,?) RETURNING id
				""", Long.class, properties.guard().profile(), properties.guard().model(), content, hash, severity);
	}
	public void registerSeed(long vectorId, String content, String hash, int severity) {
		long id = jdbc.queryForObject("""
				INSERT INTO counseling.safety_sentences
				(profile_version,model_name,content,normalized_sha256,severity,indexed_revision,indexed_vector_id)
				VALUES (?,?,?,?,?,1,?) RETURNING id
				""", Long.class, properties.guard().profile(), properties.guard().model(), content, hash, severity, vectorId);
		audit(id, null, "SEED");
	}
	public void deactivateVector(SafetySentence sentence) {
		if (sentence.vectorId() != null) {
			jdbc.update("UPDATE counseling.safety_filter_vectors SET active=false WHERE id=?", sentence.vectorId());
		}
	}
	public void update(long id, String content, String hash, int severity, boolean enabled, boolean preserveIndex) {
		jdbc.update("""
				UPDATE counseling.safety_sentences SET content=?,normalized_sha256=?,severity=?,enabled=?,
				indexed_revision=CASE WHEN ? THEN revision+1 ELSE indexed_revision END,
				revision=revision+1,index_token=NULL,index_started_at=NULL,index_error=NULL,updated_at=now() WHERE id=?
				""", content, hash, severity, enabled, preserveIndex, id);
		if (preserveIndex) {
			jdbc.update("""
					UPDATE counseling.safety_filter_vectors SET severity=?
					WHERE id=(SELECT indexed_vector_id FROM counseling.safety_sentences WHERE id=?)
					""", severity, id);
		}
	}
	public void delete(long id) {
		jdbc.update("""
				UPDATE counseling.safety_sentences SET deleted_at=now(),enabled=false,revision=revision+1,
				index_token=NULL,index_started_at=NULL,index_error=NULL,updated_at=now() WHERE id=?
				""", id);
	}
	public void beginIndex(long id, UUID token) {
		jdbc.update("""
				UPDATE counseling.safety_sentences SET index_token=?,index_started_at=now(),index_error=NULL,updated_at=now()
				WHERE id=?
				""", token, id);
	}
	public void failIndex(long id, String code) {
		jdbc.update("""
				UPDATE counseling.safety_sentences SET index_token=NULL,index_started_at=NULL,index_error=?,updated_at=now()
				WHERE id=?
				""", code, id);
	}
	public void completeIndex(SafetySentence sentence, String vector) {
		deactivateVector(sentence);
		long vectorId = jdbc.queryForObject("""
				INSERT INTO counseling.safety_filter_vectors(category,example_text,embedding,severity,model_name,profile_version)
				VALUES ('INSULT',?,CAST(? AS public.vector),?,?,?) RETURNING id
				""", Long.class, sentence.content(), vector, sentence.severity(), properties.guard().model(), properties.guard().profile());
		jdbc.update("""
				UPDATE counseling.safety_sentences SET indexed_vector_id=?,indexed_revision=revision,
				index_token=NULL,index_started_at=NULL,index_error=NULL,updated_at=now() WHERE id=?
				""", vectorId, sentence.id());
	}
	public void audit(long id, Long adminId, String action) {
		jdbc.update("""
				INSERT INTO counseling.safety_sentence_audit
				(sentence_id,admin_id,action,revision,content,severity,enabled,vector_id,error_code)
				SELECT id,?,?,revision,content,severity,enabled,indexed_vector_id,index_error
				FROM counseling.safety_sentences WHERE id=?
				""", adminId, action, id);
	}
}
