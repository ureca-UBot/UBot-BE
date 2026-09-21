package com.ubot.auth.util;

import com.ubot.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.util.Date;

@Component
public class JwtUtil {
	private final SecretKey secretKey;
	private final Long accessTokenExpirationMillis;

	public JwtUtil(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.access-token-expiration-millis}") Long accessTokenExpirationMillis
	){
		this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
		this.accessTokenExpirationMillis = accessTokenExpirationMillis;
	}

	public String createAccessToken(User user){
		Date now = new Date();
		Date expiration = new Date(now.getTime() + accessTokenExpirationMillis);

		return Jwts.builder()
				.subject(String.valueOf(user.getId()))
				.claim("role", user.getRole().name())
				.issuedAt(now)
				.expiration(expiration)
				.signWith(secretKey)
				.compact();
	}

	public Long getUserId(String token){
		String subject = Jwts.parser()
				.verifyWith(secretKey)
				.build()
				.parseSignedClaims(token)
				.getPayload()
				.getSubject();

		return Long.valueOf(subject);
	}
}
