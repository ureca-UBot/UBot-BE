package com.ubot.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.pgvector.PGvector;
import com.ubot.embedding.config.EmbeddingClientFactory;

@EnabledIfEnvironmentVariable(named = "EMBEDDING_LIVE_BASE_URL", matches = ".+")
class OpenAiCompatibleEmbeddingClientLiveTest {

	// 채팅 질문으로 받을 수 있는 최대 길이입니다(ChatRequestDto).
	private static final int MAX_QUESTION_LENGTH = 4000;

	private final EmbeddingClient client =
	        new EmbeddingClientFactory().createOpenAiCompatible(
	                RestClient.builder(),
	                System.getenv("EMBEDDING_LIVE_BASE_URL"),
	                System.getenv().getOrDefault(
	                        "EMBEDDING_LIVE_MODEL",
	                        "ubot-embedding"
	                ),
	                Duration.ofSeconds(3),
	                Duration.ofSeconds(120)
	        );

	@Test
	void embedsQuestionInto1024Dimensions() {
		PGvector vector = client.embed("유심 재발급은 어떻게 하나요?");

		assertThat(vector.toArray()).hasSize(EmbeddingClient.DIMENSIONS);
	}

	@Test
	void embedsBatchInInputOrder() {
		List<String> questions = List.of("유심 재발급 방법", "요금제 변경 방법", "가까운 매장 위치");

		List<PGvector> batch = client.embedBatch(questions);

		// 한 문장씩 따로 보낸 결과와 같은 자리에 같은 벡터가 와야 합니다.
		assertThat(batch).hasSize(questions.size());
		for (int index = 0; index < questions.size(); index++) {
			assertThat(cosineSimilarity(batch.get(index), client.embed(questions.get(index)))).isGreaterThan(0.999);
		}
	}

	@Test
	void placesSimilarQuestionsCloserThanUnrelatedOnes() {
		PGvector question = client.embed("유심 재발급 방법");
		PGvector similar = client.embed("유심을 다시 발급받으려면 어떻게 하나요");
		PGvector unrelated = client.embed("오늘 서울 날씨 알려줘");

		assertThat(cosineSimilarity(question, similar)).isGreaterThan(cosineSimilarity(question, unrelated));
	}

	@Test
	void embedsQuestionOfMaximumLength() {
		String question = "요금제 변경과 유심 재발급 방법을 알려주세요. ".repeat(200).substring(0, MAX_QUESTION_LENGTH);

		assertThat(client.embed(question).toArray()).hasSize(EmbeddingClient.DIMENSIONS);
	}

	private double cosineSimilarity(PGvector left, PGvector right) {
		float[] a = left.toArray();
		float[] b = right.toArray();
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int index = 0; index < a.length; index++) {
			dot += a[index] * b[index];
			normA += a[index] * a[index];
			normB += b[index] * b[index];
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}
}
