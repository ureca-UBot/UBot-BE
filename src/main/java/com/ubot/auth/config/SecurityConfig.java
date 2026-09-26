package com.ubot.auth.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
	private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

	@Bean
	public PasswordEncoder passwordEncoder(){
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.csrf(csrf -> csrf.disable())
				.exceptionHandling(exception -> exception
						.authenticationEntryPoint(jwtAuthenticationEntryPoint)
						.accessDeniedHandler(jwtAccessDeniedHandler)
				)
				.authorizeHttpRequests(auth -> auth
						// 최초 HTTP 요청은 JWT 인증, 비동기 응답 완료를 위한 내부 ASYNC 재디스패치는 허용합니다.
						.requestMatchers(request -> request.getDispatcherType() == DispatcherType.ASYNC
								&& request.getRequestURI().startsWith(request.getContextPath() + "/chat/"))
						.permitAll()
						.requestMatchers(
								"/auth/login",
								"/auth/signup",
								"/auth/refresh",
								"/actuator/health",
								"/v3/api-docs/**",
						        "/swagger-ui/**",
						        "/swagger-ui.html"
						).permitAll()
						.requestMatchers(
								"/admin/**"
						).hasRole("ADMIN")
						.requestMatchers("/chat/**").authenticated()
						.anyRequest().authenticated()
				)
				.addFilterBefore(
						jwtAuthenticationFilter,
						UsernamePasswordAuthenticationFilter.class
				)
				.build();
	}
}
