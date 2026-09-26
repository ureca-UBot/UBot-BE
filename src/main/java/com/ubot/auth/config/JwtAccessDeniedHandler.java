package com.ubot.auth.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.ubot.auth.exception.AuthErrorCode;
import com.ubot.auth.exception.AuthException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {
	private final HandlerExceptionResolver handlerExceptionResolver;

	public JwtAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver")
            HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

	@Override
	public void handle(HttpServletRequest request,
					   HttpServletResponse response,
					   AccessDeniedException accessDeniedException
	) {
		handlerExceptionResolver.resolveException(request, response, null, new AuthException(AuthErrorCode.FORBIDDEN));
	}
}
