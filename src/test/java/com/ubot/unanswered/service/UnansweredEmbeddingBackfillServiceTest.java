package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.entity.UnansweredQuestion;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;

@DisplayName("미응답 임베딩 백필 서비스 테스트")
class UnansweredEmbeddingBackfillServiceTest {

	private final UnansweredQuestionRepository unansweredQuestionRepository =
			mock(UnansweredQuestionRepository.class);

	private final UnansweredQuestionVectorRepository vectorRepository =
			mock(UnansweredQuestionVectorRepository.class);

	private final EmbeddingService embeddingService =
			mock(EmbeddingService.class);

	private final EmbeddingProfileService embeddingProfileService =
			mock(EmbeddingProfileService.class);

	private final UnansweredEmbeddingBackfillService service =
			new UnansweredEmbeddingBackfillService(
					unansweredQuestionRepository,
					vectorRepository,
					embeddingService,
					embeddingProfileService
			);

	@Test
	@DisplayName("현재 프로필 벡터가 없는 미응답 질문만 임베딩한다")
	void backfillCurrentProfile_embedsOnlyMissingQuestions() {
		Long profileId = 10L;

		UnansweredQuestion existing =
				question(
						1L,
						"이미 벡터가 있는 질문"
				);

		UnansweredQuestion missing =
				question(
						2L,
						"벡터가 없는 질문"
				);

		when(embeddingProfileService.getCurrentProfileId())
				.thenReturn(profileId);

		when(unansweredQuestionRepository.findAll(any(Pageable.class)))
				.thenReturn(
						new PageImpl<>(
								List.of(existing, missing)
						)
				);

		when(
				vectorRepository.hasQuestionEmbedding(
						1L,
						profileId
				)
		).thenReturn(true);

		when(
				vectorRepository.hasQuestionEmbedding(
						2L,
						profileId
				)
		).thenReturn(false);

		PGvector vector = vector();

		when(
				embeddingService.embedText(
						"벡터가 없는 질문"
				)
		).thenReturn(vector);

		int updated =
				service.backfillCurrentProfile();

		assertThat(updated)
				.isEqualTo(1);

		verify(embeddingService, never())
				.embedText(
						"이미 벡터가 있는 질문"
				);

		verify(embeddingService)
				.embedText(
						"벡터가 없는 질문"
				);

		verify(vectorRepository)
				.saveQuestionEmbedding(
						2L,
						profileId,
						vector
				);

		verify(vectorRepository)
				.rebuildGroupEmbeddings(
						profileId
				);
	}

	@Test
	@DisplayName("현재 프로필의 미응답 질문 벡터가 모두 있으면 재임베딩하지 않는다")
	void backfillCurrentProfile_skipsExistingQuestions() {
		Long profileId = 10L;

		UnansweredQuestion question =
				question(
						1L,
						"질문"
				);

		when(embeddingProfileService.getCurrentProfileId())
				.thenReturn(profileId);

		when(unansweredQuestionRepository.findAll(any(Pageable.class)))
				.thenReturn(
						new PageImpl<>(
								List.of(question)
						)
				);

		when(
				vectorRepository.hasQuestionEmbedding(
						1L,
						profileId
				)
		).thenReturn(true);

		int updated =
				service.backfillCurrentProfile();

		assertThat(updated)
				.isZero();

		verify(
				embeddingService,
				never()
		).embedText(any());

		verify(
				vectorRepository,
				never()
		).saveQuestionEmbedding(
				anyLong(),
				anyLong(),
				any()
		);

		verify(vectorRepository)
				.rebuildGroupEmbeddings(
						profileId
				);
	}

	private UnansweredQuestion question(
			Long id,
			String text
	) {
		return UnansweredQuestion.builder()
				.id(id)
				.question(text)
				.build();
	}

	private PGvector vector() {
		float[] values = new float[1024];
		values[0] = 1.0f;

		return new PGvector(values);
	}
}
