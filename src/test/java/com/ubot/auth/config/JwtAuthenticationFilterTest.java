package com.ubot.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;
import com.ubot.auth.service.AccessTokenRevocationService;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.job.service.ChatJobReadExecutor;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

class JwtAuthenticationFilterTest {

	private final JwtUtil jwtUtil = mock(JwtUtil.class);
	private final UserRepository users = mock(UserRepository.class);
	private final AccessTokenRevocationService revocations = mock(AccessTokenRevocationService.class);
	private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
	private final ChatJobReadExecutor reads = mock(ChatJobReadExecutor.class);
	private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, users, revocations, resolver, reads);

	@BeforeEach
	void validToken() {
		when(jwtUtil.getUserId("token")).thenReturn(7L);
		when(users.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(User.builder().id(7L).role(UserRole.USER).build()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void onlyJobStatusReadRunsUserLookupWithinReadBudget() throws Exception {
		when(reads.execute(any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());

		filter.doFilter(request("GET", "/chat/jobs/12"), new MockHttpServletResponse(), new MockFilterChain());
		verify(reads).execute(any(), any());

		// 다른 API는 조회 예산 없이 요청 스레드에서 바로 사용자를 찾음
		filter.doFilter(request("GET", "/users/me"), new MockHttpServletResponse(), new MockFilterChain());
		filter.doFilter(request("POST", "/chat/questions"), new MockHttpServletResponse(), new MockFilterChain());
		filter.doFilter(request("POST", "/chat/jobs/12/cancel"), new MockHttpServletResponse(), new MockFilterChain());
		verifyNoMoreInteractions(reads);
		verify(users, times(4)).findByIdAndDeletedAtIsNull(7L);
	}

	@Test
	void revocationLookupTimeoutStopsRequestWithUnavailableCode() throws Exception {
		when(revocations.isRevoked(eq(7L), any()))
				.thenThrow(new MyJwtException(JwtErrorCode.REVOCATION_CHECK_UNAVAILABLE));
		var chain = new MockFilterChain();

		filter.doFilter(request("GET", "/users/me"), new MockHttpServletResponse(), chain);

		verify(resolver).resolveException(any(), any(), isNull(), argThat(exception ->
				exception instanceof MyJwtException jwt && jwt.getErrorCode() == JwtErrorCode.REVOCATION_CHECK_UNAVAILABLE));
		assertThat(chain.getRequest()).as("다음 필터로 넘기지 않음").isNull();
		verifyNoInteractions(users, reads);
	}

	private static MockHttpServletRequest request(String method, String path) {
		var request = new MockHttpServletRequest(method, path);
		request.addHeader("Authorization", "Bearer token");
		return request;
	}
}
