package com.ubot.embedding.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.service.FaqEmbeddingBackfillService;
import com.ubot.unanswered.service.UnansweredEmbeddingBackfillService;

@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillRunnerTest {

	@Mock
	private Flyway flyway;

	@Mock
	private MigrationInfoService migrationInfoService;

	@Mock
	private FaqEmbeddingBackfillService faqEmbeddingBackfillService;

	@Mock
	private UnansweredEmbeddingBackfillService unansweredEmbeddingBackfillService;

	@Mock
	private ConfigurableApplicationContext context;

	@InjectMocks
	private EmbeddingBackfillRunner runner;

	@Test
	@DisplayName("적용되지 않은 마이그레이션이 없으면 FAQ와 미응답 질문을 차례로 백필한다")
	void backfillsWhenNoMigrationIsPending() {
		pendingMigrations(0);

		assertThat(runner.backfill())
				.isEqualTo(EmbeddingBackfillRunner.EXIT_COMPLETED);

		InOrder order = inOrder(
				faqEmbeddingBackfillService,
				unansweredEmbeddingBackfillService
		);
		order.verify(faqEmbeddingBackfillService).backfillCurrentProfile();
		order.verify(unansweredEmbeddingBackfillService).backfillCurrentProfile();
	}

	@Test
	@DisplayName("적용되지 않은 마이그레이션이 있으면 백필하지 않고 전용 종료 코드를 돌려준다")
	void skipsBackfillWhenMigrationIsPending() {
		pendingMigrations(1);

		assertThat(runner.backfill())
				.isEqualTo(EmbeddingBackfillRunner.EXIT_PENDING_MIGRATIONS);

		verifyNoInteractions(
				faqEmbeddingBackfillService,
				unansweredEmbeddingBackfillService
		);
	}

	@Test
	@DisplayName("백필이 실패하면 실패 종료 코드를 돌려주고 다음 백필은 실행하지 않는다")
	void reportsFailureWhenBackfillFails() {
		pendingMigrations(0);

		when(faqEmbeddingBackfillService.backfillCurrentProfile())
				.thenThrow(new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT));

		assertThat(runner.backfill())
				.isEqualTo(EmbeddingBackfillRunner.EXIT_FAILED);

		verify(unansweredEmbeddingBackfillService, never())
				.backfillCurrentProfile();
	}

	@Test
	@DisplayName("백필 실행 설정이 켜졌을 때만 마이그레이션을 건너뛰는 전략을 등록한다")
	void registersSkipMigrationStrategyOnlyInBackfillRun() {
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withUserConfiguration(EmbeddingBackfillRunConfig.class);

		contextRunner.run(applicationContext ->
				assertThat(applicationContext)
						.doesNotHaveBean(FlywayMigrationStrategy.class)
		);

		contextRunner
				.withPropertyValues(EmbeddingBackfillRunner.RUN_PROPERTY + "=true")
				.run(applicationContext -> {
					assertThat(applicationContext)
							.hasSingleBean(FlywayMigrationStrategy.class);

					applicationContext.getBean(FlywayMigrationStrategy.class)
							.migrate(flyway);

					verify(flyway, never()).migrate();
				});
	}

	private void pendingMigrations(int count) {
		when(flyway.info())
				.thenReturn(migrationInfoService);

		when(migrationInfoService.pending())
				.thenReturn(new MigrationInfo[count]);
	}
}
