package com.ubot.auth.config;

import com.ubot.chat.job.service.ChatJobReadExecutor;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
@Import(ChatJobReadExecutor.class)
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
								"/swagger-ui.html",
								"/rankings")
						.permitAll()
						.requestMatchers(HttpMethod.GET,
								"/plans", "/plans/**",
								"/bundle-products", "/bundle-products/**",
								"/addon-services", "/addon-services/**",
								"/roaming-products", "/roaming-products/**",
								"/home-products", "/home-products/**",
								"/notices", "/notices/**")
						.permitAll()
						.requestMatchers(HttpMethod.POST, "/chat/questions", "/chat/questions/retries").permitAll()
						// 상품 목록의 조건 선택지로 다시 조회함. 공개 상품 정보라 비회원도 씀
						.requestMatchers(HttpMethod.POST, "/chat/product-search", "/chat/product-search/options").permitAll()
						// 게스트도 자기 작업을 조회함. 소유권은 ChatJobQueryService가 확인함
						.requestMatchers(HttpMethod.GET, "/chat/jobs/*").permitAll()
						.requestMatchers(HttpMethod.POST, "/chat/jobs/*/cancel").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						.requestMatchers("/conversations/faq-selections").hasRole("USER")
						.anyRequest().authenticated())
				.addFilterBefore(
						jwtAuthenticationFilter,
						UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
