package com.ubot.global.config;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// worker 역할은 /actuator만 받고 나머지는 인증 전에 404(G-004)로 거절함
@Component
@ConditionalOnExpression(AppRole.WORKER_ONLY)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkerActuatorOnlyFilter extends OncePerRequestFilter {
	private static final String ACTUATOR_PATH = "/actuator";

	private final HandlerExceptionResolver handlerExceptionResolver;

	public WorkerActuatorOnlyFilter(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
		this.handlerExceptionResolver = handlerExceptionResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (path.equals(ACTUATOR_PATH) || path.startsWith(ACTUATOR_PATH + "/")) {
			chain.doFilter(request, response);
			return;
		}
		handlerExceptionResolver.resolveException(request, response, null,
				new GlobalException(CommonErrorCode.RESOURCE_NOT_FOUND));
	}
}
