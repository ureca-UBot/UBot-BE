package com.ubot.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.chat.job.support.ChatJobTestDriver;
import com.ubot.common.ErrorCode;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 질문 접수(202) → Worker 처리 → 같은 회원·게스트 세션으로 상태 조회
// 테스트 프로필은 Worker가 스스로 가져가지 않으므로 processAvailableJobs()를 직접 부름
final class ChatJobTestFlow {
	private static final Pattern JOB_ID = Pattern.compile("\"jobId\":(\\d+)");

	private final MockMvc mvc;
	private final ChatJobTestDriver driver;

	ChatJobTestFlow(MockMvc mvc, ChatJobTestDriver driver) {
		this.mvc = mvc;
		this.driver = driver;
	}

	MvcResult submit(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.status").value("QUEUED"))
				.andReturn();
	}

	// expectedError가 없으면 SUCCEEDED, 있으면 그 코드로 FAILED여야 함. 조회 응답 본문을 돌려줌
	String complete(MockHttpServletRequestBuilder request, ErrorCode expectedError) throws Exception {
		MvcResult submitted = submit(request);
		driver.processAvailableJobs();
		var result = mvc.perform(statusOf(submitted))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value(expectedError == null ? "SUCCEEDED" : "FAILED"));
		if (expectedError != null) {
			result.andExpect(jsonPath("$.data.errorCode").value(expectedError.getCode()));
		}
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	// 접수 때와 같은 회원 토큰·게스트 세션으로 조회함
	MockHttpServletRequestBuilder statusOf(MvcResult submitted) throws Exception {
		var request = get("/chat/jobs/{jobId}", jobId(submitted));
		String authorization = submitted.getRequest().getHeader("Authorization");
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		HttpSession session = submitted.getRequest().getSession(false);
		if (session instanceof MockHttpSession mockSession && !mockSession.isInvalid()) {
			request.session(mockSession);
		}
		return request;
	}

	static long jobId(MvcResult submitted) throws Exception {
		var matcher = JOB_ID.matcher(submitted.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(matcher.find()).as("접수 응답에 jobId가 있음").isTrue();
		return Long.parseLong(matcher.group(1));
	}
}
