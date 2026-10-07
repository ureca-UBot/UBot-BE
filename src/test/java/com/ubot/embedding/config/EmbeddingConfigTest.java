package com.ubot.embedding.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.client.OllamaEmbeddingClient;
import com.ubot.embedding.client.OpenAiCompatibleEmbeddingClient;
import com.ubot.embedding.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class EmbeddingConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> context.getBeanFactory()
					.setConversionService(ApplicationConversionService.getSharedInstance()))
			.withUserConfiguration(EmbeddingConfig.class, EmbeddingService.class)
			.withBean(RestClient.Builder.class, RestClient::builder);

	private final ApplicationContextRunner ollamaRunner = contextRunner.withPropertyValues(
			"ollama.base-url=http://127.0.0.1:1", "ollama.embedding.model=ollama-model");

	@Test
	void selectsOllamaClientWhenProviderIsMissingOrOllama() {
		ollamaRunner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(EmbeddingClient.class)
				.getBean(EmbeddingClient.class).isInstanceOf(OllamaEmbeddingClient.class));
		ollamaRunner.withPropertyValues("EMBEDDING_PROVIDER=ollama")
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(EmbeddingClient.class)
						.getBean(EmbeddingClient.class).isInstanceOf(OllamaEmbeddingClient.class));
	}

	@Test
	void selectsOpenAiCompatibleClientWithoutOllamaSettings() {
		// Ollama 설정이 없어도 OpenAI 호환 구현체만으로 기동합니다. 기동할 때 서버에 접속하지 않습니다.
		contextRunner.withPropertyValues("EMBEDDING_PROVIDER=openai-compatible")
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(EmbeddingClient.class)
						.getBean(EmbeddingClient.class).isInstanceOf(OpenAiCompatibleEmbeddingClient.class));
	}

	@Test
	void reportsModelNameOfSelectedProvider() {
		// 두 모델명이 모두 설정돼 있어도, 시도 기록에 남길 이름은 선택된 provider의 것입니다.
		var runner = ollamaRunner.withPropertyValues("EMBEDDING_MODEL=served-model");

		runner.run(context -> assertThat(context.getBean(EmbeddingService.class).getModelName())
				.isEqualTo("ollama-model"));
		runner.withPropertyValues("EMBEDDING_PROVIDER=openai-compatible")
				.run(context -> assertThat(context.getBean(EmbeddingService.class).getModelName())
						.isEqualTo("served-model"));
	}

	@Test
	void usesServedModelNameAsDefaultForOpenAiCompatible() {
		contextRunner.withPropertyValues("EMBEDDING_PROVIDER=openai-compatible")
				.run(context -> assertThat(context.getBean(EmbeddingService.class).getModelName())
						.isEqualTo("ubot-embedding"));
	}

	@Test
	void rejectsUnlimitedTimeoutForOpenAiCompatible() {
		contextRunner.withPropertyValues("EMBEDDING_PROVIDER=openai-compatible", "EMBEDDING_READ_TIMEOUT=0s")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
				});
	}

	@Test
	void failsToStartWithUnknownProvider() {
		ollamaRunner.withPropertyValues("EMBEDDING_PROVIDER=unknown")
				.run(context -> assertThat(context).hasFailed());
	}
}
