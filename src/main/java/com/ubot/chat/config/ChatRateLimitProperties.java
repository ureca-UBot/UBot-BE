package com.ubot.chat.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회원 채팅 접수(질문·재시도·의도 재검색)의 사용자 ID별 제한입니다.
 * Nginx의 토큰별 제한은 토큰을 재발급하면 새로 시작하므로, 사용자 기준 한도는 여기서 지킵니다.
 */
@ConfigurationProperties(prefix = "app.chat.member-rate-limit")
public class ChatRateLimitProperties implements InitializingBean {
	private boolean enabled = true;
	private int maxRequests = 30;
	private Duration window = Duration.ofSeconds(60);

	@Override
	public void afterPropertiesSet() {
		if (maxRequests < 1) {
			throw new IllegalStateException("CHAT_USER_RATE_LIMIT_MAX는 1 이상이어야 합니다.");
		}
		// Redis EXPIRE는 초 단위라 1초보다 짧은 윈도는 만료 시간을 정할 수 없습니다.
		if (window == null || window.toSeconds() < 1) {
			throw new IllegalStateException("CHAT_USER_RATE_LIMIT_WINDOW는 1초 이상이어야 합니다.");
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getMaxRequests() {
		return maxRequests;
	}

	public void setMaxRequests(int maxRequests) {
		this.maxRequests = maxRequests;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}
}
