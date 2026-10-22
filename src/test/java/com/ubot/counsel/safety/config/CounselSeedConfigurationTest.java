package com.ubot.counsel.safety.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.counsel.safety.service.SafetyProfileService;
import java.io.IOException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CounselSeedConfigurationTest {
	private final Flyway flyway = mock(Flyway.class);
	private final SafetyProfileService profiles = mock(SafetyProfileService.class);
	private final CounselSeedConfiguration configuration = new CounselSeedConfiguration();

	@Test
	void pendingMigrationsSkipIndexAndWorkerChecks() {
		pendingMigrations(1);
		assertThat(configuration.seed(flyway, profiles, false)).isEqualTo(3);
		verify(flyway, never()).validate();
		verify(flyway, never()).migrate();
		verifyNoInteractions(profiles);
	}

	@Test
	void preflightChecksMigrationsWithoutRequiringWorker() {
		pendingMigrations(0);
		assertThat(configuration.seed(flyway, profiles, true)).isZero();
		verifyNoInteractions(profiles);
	}

	@Test
	void migratedSchemaSeedsAndVerifiesWorkerInOrder() throws Exception {
		pendingMigrations(0);
		assertThat(configuration.seed(flyway, profiles, false)).isZero();
		var order = inOrder(profiles);
		order.verify(profiles).seedProfile();
		order.verify(profiles).verifyReady();
	}

	@Test
	void checksumMismatchFailsBeforeIndexing() {
		pendingMigrations(0);
		doThrow(new IllegalStateException("checksum mismatch")).when(flyway).validate();
		assertThat(configuration.seed(flyway, profiles, true)).isEqualTo(1);
		verifyNoInteractions(profiles);
	}

	@Test
	void seedFailureDoesNotReportReady() throws Exception {
		pendingMigrations(0);
		doThrow(new IOException("unreadable seed")).when(profiles).seedProfile();
		assertThat(configuration.seed(flyway, profiles, false)).isEqualTo(1);
		verify(profiles, never()).verifyReady();
	}

	@Test
	void workerReadinessFailureFailsDeployment() {
		pendingMigrations(0);
		doThrow(new IllegalStateException("worker timeout")).when(profiles).verifyReady();
		assertThat(configuration.seed(flyway, profiles, false)).isEqualTo(1);
	}

	@Test
	void onlySeedOnlyRunSkipsAutomaticMigrations() {
		var context = new ApplicationContextRunner()
				.withUserConfiguration(CounselSeedConfiguration.class)
				.withBean(Flyway.class, () -> flyway)
				.withBean(SafetyProfileService.class, () -> profiles);
		context.run(app -> assertThat(app).doesNotHaveBean(FlywayMigrationStrategy.class));
		context.withPropertyValues("counsel.seed-on-start=true")
				.run(app -> assertThat(app).doesNotHaveBean(FlywayMigrationStrategy.class));
		context.withPropertyValues("counsel.seed-on-start=true", "counsel.seed-only=true")
				.run(app -> {
					assertThat(app).hasSingleBean(FlywayMigrationStrategy.class);
					app.getBean(FlywayMigrationStrategy.class).migrate(flyway);
					verify(flyway, never()).migrate();
				});
	}

	private void pendingMigrations(int count) {
		var info = mock(MigrationInfoService.class);
		when(flyway.info()).thenReturn(info);
		when(info.pending()).thenReturn(new MigrationInfo[count]);
	}
}
