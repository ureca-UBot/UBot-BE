package com.ubot.counsel.safety.repository;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.model.GuardDecision;
import com.ubot.counsel.safety.util.SafetyTextNormalizer;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SafetyRepository {
	private static final String RULE_VERSION = "forbidden-word-semantic-v2";

	public record Match(long id, double score, String category) {}
	private final JdbcTemplate jdbc;
	private final CounselProperties.Guard config;
	public SafetyRepository(JdbcTemplate jdbc, CounselProperties properties) {
		this.jdbc = jdbc;
		this.config = properties.guard();
	}
	public List<Match> findTopMatches(String vector) {
		return jdbc.query("""
				SELECT v.id, 1 - (v.embedding OPERATOR(public.<=>) CAST(? AS public.vector)) AS score, v.category
				FROM counseling.safety_filter_vectors v
				JOIN counseling.guard_profiles p ON p.profile_version=v.profile_version AND p.model_name=v.model_name
				WHERE v.active AND v.model_name=? AND v.profile_version=? AND p.preprocessing_version=?
				ORDER BY v.embedding OPERATOR(public.<=>) CAST(? AS public.vector), v.id LIMIT ?
				""", (r, n) -> new Match(r.getLong("id"), r.getDouble("score"), r.getString("category")),
				vector, config.model(), config.profile(), SafetyTextNormalizer.PREPROCESSING, vector, config.topK());
	}
	public void event(long messageId, GuardDecision decision) {
		jdbc.update("""
				INSERT INTO counseling.safety_filter_events
				(message_id,decision,matched_vector_id,score,threshold,model_name,profile_version,rule_version,category,error_code)
				VALUES (?,?,?,?,?,?,?,?,?,?)
				""", messageId, decision.status(), decision.vectorId(), decision.score(), config.threshold(),
				config.model(), config.profile(), RULE_VERSION, decision.category(), decision.errorCode());
	}
	public void recordConfiguration() {
		jdbc.update("""
				INSERT INTO counseling.guard_config_history(model_name,profile_version,rule_version,threshold)
				VALUES (?,?,?,?)
				""", config.model(), config.profile(), RULE_VERSION, config.threshold());
	}
}
