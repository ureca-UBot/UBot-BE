package com.ubot.auth.config;

import com.ubot.auth.util.JwtUtil;
import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;
	private final HandlerExceptionResolver handlerExceptionResolver;

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
			resolveException(request, response, new MyJwtException(JwtErrorCode.EXPIRED_ACCESS_TOKEN));
			return;
		} catch (JwtException | IllegalArgumentException e){
			resolveException(request, response, new MyJwtException(JwtErrorCode.INVALID_ACCESS_TOKEN));
			return;
		}

		User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);

		if(user == null) {
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
