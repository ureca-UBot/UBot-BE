package com.ubot.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.chat.job.worker.ChatJobWorker;
import com.ubot.ranking.scheduler.RankingScheduler;
import com.ubot.retention.scheduler.DataRetentionScheduler;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "app.role=worker")
@AutoConfigureMockMvc
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("worker 역할 테스트")
class WorkerRoleIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired ApplicationContext context;

	@Test
	@DisplayName("actuator 요청은 그대로 처리한다")
	void allowsActuator() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(jsonPath("$.status").exists())
				.andExpect(jsonPath("$.code").doesNotExist());
	}

	@Test
	@DisplayName("actuator가 아닌 요청은 인증 여부와 관계없이 404 G-004로 거절한다")
	void rejectsOtherRequests() throws Exception {
		expectNotFound(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"password\":\"password\"}"));
		expectNotFound(post("/chat/questions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"유심 재발급\"}"));
		expectNotFound(get("/chat/jobs/{jobId}", UUID.randomUUID()));
		expectNotFound(get("/admin/faqs"));
		expectNotFound(get("/actuatorx"));
	}

	@Test
	@DisplayName("답변 Worker는 만들고 스케줄러는 만들지 않는다")
	void createsWorkerWithoutSchedulers() {
		assertThat(context.getBeanNamesForType(ChatJobWorker.class)).hasSize(1);
		assertThat(context.getBeanNamesForType(RankingScheduler.class)).isEmpty();
		assertThat(context.getBeanNamesForType(DataRetentionScheduler.class)).isEmpty();
	}

	private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
		mvc.perform(request)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("G-004"));
	}
}
