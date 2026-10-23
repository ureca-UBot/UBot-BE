package com.ubot.auth.config;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import lombok.extern.slf4j.Slf4j;

import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;
import com.ubot.auth.service.AccessTokenRevocationService;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.service.ChatJobReadExecutor;
import com.ubot.user.entity.User;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.repository.UserRepository;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;
	private final AccessTokenRevocationService accessTokenRevocationService;
	private final HandlerExceptionResolver handlerExceptionResolver;
	private final ChatJobReadExecutor chatJobReadExecutor;

	// 사용자 정보를 쓰지 않는 공개 조회 경로입니다. 토큰 상태와 상관없이 처리하려고 토큰 검사를 건너뜁니다.
	private static final String TOKEN_FREE_GET_PATH = "/notices";
	private static final Pattern JOB_STATUS_PATH = Pattern.compile("/chat/jobs/[^/]+");

	public JwtAuthenticationFilter(
			JwtUtil jwtUtil,
			UserRepository userRepository,
			AccessTokenRevocationService accessTokenRevocationService,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
			ChatJobReadExecutor chatJobReadExecutor) {
		this.jwtUtil = jwtUtil;
		this.userRepository = userRepository;
		this.accessTokenRevocationService = accessTokenRevocationService;
		this.handlerExceptionResolver = handlerExceptionResolver;
		this.chatJobReadExecutor = chatJobReadExecutor;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())) {
			return false;
		}
		String path = path(request);
		return path.equals(TOKEN_FREE_GET_PATH) || path.startsWith(TOKEN_FREE_GET_PATH + "/");
	}

	// 답변 작업 조회는 DB가 멈춰도 오래 붙잡히지 않게 사용자 조회를 조회 예산 안에서 실행함
	private static boolean isJobStatusRead(HttpServletRequest request) {
		return "GET".equals(request.getMethod()) && JOB_STATUS_PATH.matcher(path(request)).matches();
	}

	private static String path(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length());
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String token = resolveToken(request);

		if (token == null) {
			filterChain.doFilter(request, response);
			return;
		}

		Long userId;

		try {
			userId = jwtUtil.getUserId(token);
		} catch (ExpiredJwtException e) {
			log.info("만료된 접근 토큰이 전달되었습니다: 경로={}", request.getRequestURI());
			resolveException(request, response, new MyJwtException(JwtErrorCode.EXPIRED_ACCESS_TOKEN));
			return;
		} catch (JwtException | IllegalArgumentException e) {
			log.warn("유효하지 않은 접근 토큰이 전달되었습니다: 경로={}", request.getRequestURI());
			resolveException(request, response, new MyJwtException(JwtErrorCode.INVALID_ACCESS_TOKEN));
			return;
		}

		if (accessTokenRevocationService.isRevoked(userId, jwtUtil.getIssuedAt(token))) {
			log.info("로그아웃된 접근 토큰이 전달되었습니다: 사용자ID={}, 경로={}", userId, request.getRequestURI());
			resolveException(request, response, new MyJwtException(JwtErrorCode.REVOKED_ACCESS_TOKEN));
			return;
		}

		Supplier<User> findUser = () -> userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
		User user;
		try {
			user = isJobStatusRead(request) ? chatJobReadExecutor.execute(request, findUser) : findUser.get();
		} catch (ChatException exception) {
			resolveException(request, response, exception);
			return;
		}

		if (user == null) {
			log.warn("접근 토큰의 사용자를 찾지 못했습니다: 사용자ID={}, 경로={}", userId, request.getRequestURI());
			resolveException(request, response, new UserException(UserErrorCode.USER_NOT_FOUND));
			return;
		}

		CustomUserDetails userDetails = new CustomUserDetails(user);

		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
				userDetails,
				null,
				userDetails.getAuthorities());

		SecurityContextHolder.getContext().setAuthentication(authentication);
		log.debug("접근 토큰 인증에 성공했습니다: 사용자ID={}, 경로={}", userId, request.getRequestURI());

		filterChain.doFilter(request, response);
	}

	private String resolveToken(HttpServletRequest request) {
		String authorization = request.getHeader("Authorization");

		if (authorization == null || !authorization.startsWith("Bearer ")) {
			return null;
		}

		return authorization.substring("Bearer ".length());
	}

	private void resolveException(
			HttpServletRequest request,
			HttpServletResponse response,
			Exception exception) {
		SecurityContextHolder.clearContext();

		handlerExceptionResolver.resolveException(
				request,
				response,
				null,
				exception);
	}
}
