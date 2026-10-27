package com.ubot.auth.config;

import com.ubot.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

	private final User user;

	@Override
	public String getUsername(){
		return user.getEmail();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(
				new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
		);
	}

	@Override
	public String getPassword(){
		return user.getHashedPassword();
	}

	public Long getUserId(){
		return user.getId();
	}

}
