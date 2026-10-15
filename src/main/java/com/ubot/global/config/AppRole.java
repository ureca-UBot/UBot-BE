package com.ubot.global.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 프로세스 역할(APP_ROLE)
// api: 접수·조회·나머지 API·스케줄러
// worker: 답변 Worker만. HTTP는 /actuator만 받음
// all: 둘 다(로컬·테스트 기본값)
@Component
public class AppRole {
	public static final String RUNS_WORKER = "'${app.role:all}' != 'api'";
	public static final String RUNS_SCHEDULERS = "'${app.role:all}' != 'worker'";
	public static final String WORKER_ONLY = "'${app.role:all}' == 'worker'";

	private static final Set<String> ROLES = Set.of("all", "api", "worker");

	public AppRole(@Value("${app.role:all}") String role) {
		if (!ROLES.contains(role)) {
			throw new IllegalStateException("app.role(APP_ROLE)은 all, api, worker 중 하나여야 합니다: " + role);
		}
	}
}
