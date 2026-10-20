package com.ubot.counsel.safety.service;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.util.SafetyTextNormalizer;
import com.ubot.counsel.safety.repository.SafetySentenceRepository;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SafetyProfileService {
	private record Example(String text, int severity, String vector) {}
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final EmbeddingClient embedding;
	private final CounselProperties.Guard config;
	private final SafetySentenceRepository sentences;
	public SafetyProfileService(JdbcTemplate jdbc, TransactionTemplate tx, EmbeddingClient embedding, CounselProperties properties,
			SafetySentenceRepository sentences) {
		this.jdbc = jdbc;
		this.tx = tx; this.embedding = embedding; this.config = properties.guard();
		this.sentences = sentences;
	}
	public void seedProfile() throws Exception {
		byte[] bytes = new ClassPathResource("safety/safety-positive.csv").getContentAsByteArray();
		String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		if (alreadySeeded(digest)) return;
		List<Example> examples = new ArrayList<>();
		Set<String> unique = new HashSet<>();
		try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
			 var csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
			for (var row : csv) {
				String text = row.get("example_text");
				int severity = Integer.parseInt(row.get("severity"));
				if (!row.get("category").equals("INSULT") || text.isBlank() || text.length() > 1000
						|| severity < 1 || severity > 3 || !unique.add(SafetyTextNormalizer.normalize(text))) {
					throw new IllegalArgumentException("Invalid or duplicate seed example");
				}
				examples.add(new Example(text, severity, embedding.embed(text)));
			}
		}
		if (examples.size() < 50) throw new IllegalStateException("At least 50 reviewed examples required");
		tx.executeWithoutResult(ignored -> {
			jdbc.execute("SELECT pg_advisory_xact_lock(2001021)");
			if (alreadySeeded(digest)) return;
			jdbc.update("""
					INSERT INTO counseling.guard_profiles(profile_version,model_name,preprocessing_version,dataset_sha256)
					VALUES (?,?,?,?)
					""", config.profile(), config.model(), SafetyTextNormalizer.PREPROCESSING, digest);
			for (var example : examples) {
				long vectorId = jdbc.queryForObject("""
						INSERT INTO counseling.safety_filter_vectors(category,example_text,embedding,severity,model_name,profile_version)
						VALUES ('INSULT',?,CAST(? AS public.vector),?,?,?) RETURNING id
						""", Long.class, example.text(), example.vector(), example.severity(), config.model(), config.profile());
				sentences.registerSeed(vectorId, example.text(), SafetyTextNormalizer.contentHash(example.text()), example.severity());
			}
		});
	}
	private boolean alreadySeeded(String digest) {
		var rows = jdbc.queryForList("SELECT * FROM counseling.guard_profiles WHERE profile_version=?", config.profile());
		if (rows.isEmpty()) return false;
		var existing = rows.getFirst();
		if (!digest.equals(existing.get("dataset_sha256")) || !config.model().equals(existing.get("model_name"))
				|| !SafetyTextNormalizer.PREPROCESSING.equals(existing.get("preprocessing_version"))) {
			throw new IllegalStateException("Profile is immutable; increment counsel.guard.profile and re-embed");
		}
		return true;
	}
}
