package com.ubot.unanswered.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;

@Service
public class UnansweredQuestionService {
	private final EmbeddingService embeddingService;
	private final UnansweredQuestionRepository unansweredQuestionRepository;
	private final UnansweredQuestionVectorRepository unansweredQuestionVectorRepository;
	private final double groupThreshold;

	public UnansweredQuestionService(
			EmbeddingService embeddingService,
			UnansweredQuestionRepository unansweredQuestionRepository,
			UnansweredQuestionVectorRepository unansweredQuestionVectorRepository,
			@Value("${UNANSWERED_GROUP_THRESHOLD:0.6}") double groupThreshold
	){
		this.embeddingService = embeddingService;
		this.unansweredQuestionRepository = unansweredQuestionRepository;
		this.unansweredQuestionVectorRepository = unansweredQuestionVectorRepository;
		this.groupThreshold = groupThreshold;
	}

	@Transactional
	public void createUnansweredQuestion(
			Long attemptId,
			String question,
			UnansweredReason reason,
			Long bestFaqId,
			Double bestSimilarity
	){
		if(unansweredQuestionRepository.existsByAttemptId(attemptId)){
			return;
		}

		PGvector vector = embeddingService.embedText(question);
		Long groupId = unansweredQuestionVectorRepository.findNearestGroupId(vector, groupThreshold)
				.orElseGet(() -> unansweredQuestionVectorRepository.saveGroup(question, vector, bestFaqId));

		unansweredQuestionVectorRepository.saveQuestion(attemptId, groupId, question, vector, reason, bestFaqId, bestSimilarity);
		unansweredQuestionVectorRepository.updateGroupCentroid(groupId);
	}
}
