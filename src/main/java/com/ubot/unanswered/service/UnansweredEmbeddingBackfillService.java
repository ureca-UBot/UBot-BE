package com.ubot.unanswered.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnansweredEmbeddingBackfillService {

	private static final int BATCH_SIZE = 100;

	private final UnansweredQuestionRepository unansweredQuestionRepository;
	private final UnansweredQuestionVectorRepository vectorRepository;
	private final EmbeddingService embeddingService;
	private final EmbeddingProfileService embeddingProfileService;

	public int backfillCurrentProfile() {
		Long profileId =
				embeddingProfileService.getCurrentProfileId();

		int pageNumber = 0;
		int updatedCount = 0;

		Page<UnansweredQuestion> page;

		do {
			page = unansweredQuestionRepository.findAll(
					PageRequest.of(
							pageNumber,
							BATCH_SIZE,
							Sort.by("id").ascending()
					)
			);

			for (UnansweredQuestion question : page.getContent()) {
				if (vectorRepository.hasQuestionEmbedding(
						question.getId(),
						profileId
				)) {
					continue;
				}

				PGvector vector =
						embeddingService.embedText(
								question.getQuestion()
						);

				vectorRepository.saveQuestionEmbedding(
						question.getId(),
						profileId,
						vector
				);

				updatedCount++;
			}

			pageNumber++;

		} while (page.hasNext());

		vectorRepository.rebuildGroupEmbeddings(
				profileId
		);

		log.info(
				"미응답 임베딩 백필 완료: profileId={}, updated={}",
				profileId,
				updatedCount
		);

		return updatedCount;
	}
}
