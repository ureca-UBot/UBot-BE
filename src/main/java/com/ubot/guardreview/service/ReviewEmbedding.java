package com.ubot.guardreview.service;

import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.guardreview.model.ReviewModels.Vector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewEmbedding {
	private final EmbeddingService embedding;
	private final EmbeddingProfileService profiles;
	public Vector embed(String expression) {
		return new Vector(profiles.getCurrentProfileId().toString(), embedding.embedText(expression).toArray());
	}
}
