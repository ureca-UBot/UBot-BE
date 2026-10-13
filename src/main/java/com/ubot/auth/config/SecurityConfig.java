package com.ubot.auth.config;

import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
	private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins:}") String allowedOrigins
	) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		List<String> origins = Arrays.stream(StringUtils.commaDelimitedListToStringArray(allowedOrigins))
				.map(String::trim)
				.filter(StringUtils::hasText)
				.toList();
		if (origins.isEmpty()) {
			return source;
		}
		if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
			throw new IllegalStateException("CORS_ALLOWED_ORIGINS에는 와일드카드(*) 없이 정확한 origin만 적어야 합니다.");
		}
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				// Spring Security 인증은 STATELESS로 유지하며, HttpSession은 인증 상태가 아닌 게스트 Conversation
				// 식별 용도로만 사용합니다.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				.exceptionHandling(exception -> exception
						.authenticationEntryPoint(jwtAuthenticationEntryPoint)
						.accessDeniedHandler(jwtAccessDeniedHandler))
				.authorizeHttpRequests(auth -> auth
						// 최초 HTTP 요청은 JWT 인증, 비동기 응답 완료를 위한 내부 ASYNC 재디스패치는 허용합니다.
						.requestMatchers(request -> request.getDispatcherType() == DispatcherType.ASYNC
								&& request.getRequestURI().startsWith(request.getContextPath() + "/chat/"))
						.permitAll()
						.requestMatchers(
								"/auth/login",
								"/auth/signup",
								"/auth/refresh",
								"/stores/**",
								"/locations/**",
								"/actuator/health",
								"/actuator/prometheus",
								"/v3/api-docs/**",
								"/swagger-ui/**",
								"/swagger-ui.html")
						.permitAll()
						.requestMatchers(HttpMethod.GET,
								"/plans", "/plans/**",
								"/bundle-products", "/bundle-products/**",
								"/addon-services", "/addon-services/**",
								"/roaming-products", "/roaming-products/**",
								"/notices", "/notices/**")
						.permitAll()
						.requestMatchers(HttpMethod.POST, "/chat/questions", "/chat/questions/retries").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						.anyRequest().authenticated())
				.addFilterBefore(
						jwtAuthenticationFilter,
						UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
