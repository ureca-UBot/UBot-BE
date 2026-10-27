package com.ubot.auth.entity;

import com.ubot.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name="refresh_tokens")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class RefreshToken {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	@Column(name = "token")
	private String token;

	@Column(name = "expired_at")
	private LocalDateTime expiredAt;

	@Column(name = "previous_token")
	private String previousToken;

	@Column(name = "rotated_at")
	private LocalDateTime rotatedAt;

	public void reissue(String token, LocalDateTime expiredAt) {
		this.token = token;
		this.expiredAt = expiredAt;
		this.previousToken = null;
		this.rotatedAt = null;
	}

	public void rotate(String token, LocalDateTime expiredAt, LocalDateTime rotatedAt) {
		this.previousToken = this.token;
		this.token = token;
		this.expiredAt = expiredAt;
		this.rotatedAt = rotatedAt;
	}
}
