package com.ubot.counsel.common.config;

import com.ubot.counsel.common.exception.CounselErrorCode;
import com.ubot.counsel.common.exception.CounselException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CounselProperties.class)
public class CounselConfiguration implements WebMvcConfigurer {
	private final CounselProperties properties;
	public CounselConfiguration(CounselProperties properties) { this.properties = properties; }
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
				response.setHeader("Cache-Control", "no-store");
				if (!properties.enabled()) throw new CounselException(CounselErrorCode.DISABLED);
				long maximum = request.getServletPath().equals("/counsel/requests") ? 1_048_576 : 16_384;
				if (request.getContentLengthLong() > maximum) throw new CounselException(CounselErrorCode.PAYLOAD_TOO_LARGE);
				return true;
			}
		}).addPathPatterns("/counsel/**");
	}
}
