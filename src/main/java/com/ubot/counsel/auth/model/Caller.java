package com.ubot.counsel.auth.model;

import com.ubot.counsel.common.exception.CounselException;
import com.ubot.auth.config.CustomUserDetails;

public record Caller(long id, String role) {
	public static Caller from(CustomUserDetails principal) {
		if (principal == null) throw CounselException.forbidden();
		String role = principal.getAuthorities().stream().map(authority -> authority.getAuthority())
				.filter(authority -> authority.startsWith("ROLE_")).findFirst().orElseThrow(CounselException::forbidden);
		return new Caller(principal.getUserId(), role.substring(5));
	}
	public void require(String required) {
		if (!required.equals(role)) throw CounselException.forbidden();
	}
}
