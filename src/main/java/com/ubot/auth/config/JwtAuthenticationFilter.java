package com.ubot.auth.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import lombok.extern.slf4j.Slf4j;

import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;
import com.ubot.auth.util.JwtUtil;
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
	private final HandlerExceptionResolver handlerExceptionResolver;

	public JwtAuthenticationFilter(
            JwtUtil jwtUtil,
            UserRepository userRepository,
            @Qualifier("handlerExceptionResolver")
            HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }
	
	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		String token = resolveToken(request);

		if (token == null) {
			filterChain.doFilter(request, response);
			return;
		}

		Long userId;

		try {
			userId = jwtUtil.getUserId(token);
		}catch( ExpiredJwtException e ) {
			log.warn("만료된 접근 토큰이 전달되었습니다: 경로={}", request.getRequestURI());
			resolveException(request, response, new MyJwtException(JwtErrorCode.EXPIRED_ACCESS_TOKEN));
			return;
		} catch (JwtException | IllegalArgumentException e){
			log.warn("유효하지 않은 접근 토큰이 전달되었습니다: 경로={}", request.getRequestURI());
			resolveException(request, response, new MyJwtException(JwtErrorCode.INVALID_ACCESS_TOKEN));
			return;
		}

		User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);

		if(user == null) {
			log.warn("접근 토큰의 사용자를 찾지 못했습니다: 사용자ID={}, 경로={}", userId, request.getRequestURI());
			resolveException(request, response, new UserException(UserErrorCode.USER_NOT_FOUND));
			return;
		}

		CustomUserDetails userDetails = new CustomUserDetails(user);

		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(
						userDetails,
						null,
						userDetails.getAuthorities()
				);

		SecurityContextHolder.getContext().setAuthentication(authentication);
		log.debug("접근 토큰 인증에 성공했습니다: 사용자ID={}, 경로={}", userId, request.getRequestURI());

		filterChain.doFilter(request, response);
	}

	private String resolveToken(HttpServletRequest request) {
		String authorization = request.getHeader("Authorization");

		if(authorization == null || !authorization.startsWith("Bearer ")) {
			return null;
		}

		return authorization.substring("Bearer ".length());
	}

	private void resolveException(
			HttpServletRequest request,
			HttpServletResponse response,
			Exception exception
	) {
		SecurityContextHolder.clearContext();

		handlerExceptionResolver.resolveException(
				request,
				response,
				null,
				exception
		);
	}
}
