package com.ubot.chat.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClientIpResolver {
	public String resolve(HttpServletRequest request) {
		String forwardedIp = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(forwardedIp)) {
			return forwardedIp.split(",")[0].trim();
		}

		return request.getRemoteAddr();
	}
}
