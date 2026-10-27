package com.ubot.embedding.backfill;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.ubot.faq.service.FaqEmbeddingBackfillService;
import com.ubot.unanswered.service.UnansweredEmbeddingBackfillService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지금 Embedding Profile에 없는 벡터를 채우고 프로세스를 끝냅니다.
 * 실행 인자 --app.embedding-backfill.run=true 가 있을 때만 등록되며, 결과는 종료 코드로 알립니다.
 */
@Component
@ConditionalOnProperty(name = EmbeddingBackfillRunner.RUN_PROPERTY, havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class EmbeddingBackfillRunner implements ApplicationRunner {

	public static final String RUN_PROPERTY = "app.embedding-backfill.run";

	public static final int EXIT_COMPLETED = 0;
	public static final int EXIT_FAILED = 1;
	// 적용되지 않은 마이그레이션이 있어 백필하지 않았습니다. 백엔드를 교체한 뒤 다시 실행해야 합니다.
	public static final int EXIT_PENDING_MIGRATIONS = 3;

	private final Flyway flyway;
	private final FaqEmbeddingBackfillService faqEmbeddingBackfillService;
	private final UnansweredEmbeddingBackfillService unansweredEmbeddingBackfillService;
	private final ConfigurableApplicationContext context;

	@Override
	public void run(ApplicationArguments args) {
		int exitCode = backfill();

		System.exit(SpringApplication.exit(context, () -> exitCode));
	}

	int backfill() {
		int pendingMigrations = flyway.info().pending().length;

		if (pendingMigrations > 0) {
			log.warn(
					"적용되지 않은 마이그레이션이 {}개 있어 임베딩 백필을 건너뜁니다. 백엔드를 교체한 뒤 다시 실행하세요.",
					pendingMigrations
			);
			return EXIT_PENDING_MIGRATIONS;
		}

		try {
			int faqCount = faqEmbeddingBackfillService.backfillCurrentProfile();
			int unansweredCount = unansweredEmbeddingBackfillService.backfillCurrentProfile();

			log.info(
					"임베딩 백필 실행을 마쳤습니다: FAQ={}건, 미응답 질문={}건",
					faqCount,
					unansweredCount
			);
			return EXIT_COMPLETED;
		} catch (RuntimeException exception) {
			log.error("임베딩 백필 실행에 실패했습니다.", exception);
			return EXIT_FAILED;
		}
	}
}
