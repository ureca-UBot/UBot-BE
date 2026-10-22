package com.ubot.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("지표 설정 테스트")
class MetricsConfigurationTest {

	@Autowired private MeterRegistry registry;

	@Test
	@DisplayName("application.yml의 slo 구간이 histogram 버킷으로 붙고, 모든 지표에 역할 태그가 붙는다")
	void appliesConfiguredBucketsAndRoleTag() {
		var search = registry.timer("faq.vector.search", "scope", "all");
		search.record(Duration.ofMillis(3));
		var similarity = registry.summary("faq.retrieval.top.similarity", "scope", "all");
		similarity.record(0.62);

		assertThat(search.getId().getTag("role")).isEqualTo("all");
		assertThat(Arrays.stream(search.takeSnapshot().histogramCounts()).map(bucket -> bucket.bucket(TimeUnit.MILLISECONDS)))
				.containsExactly(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0);
		assertThat(Arrays.stream(similarity.takeSnapshot().histogramCounts()).map(CountAtBucket::bucket))
				.contains(0.6, 0.65)
				.hasSize(13);
	}
}
