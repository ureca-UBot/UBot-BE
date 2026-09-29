package com.ubot.forbiddenword.entity;

import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "forbidden_words")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class ForbiddenWord {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "word")
	private String word;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private ForbiddenWordStatus status;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	public void update(String word, ForbiddenWordStatus status) {
		this.word = word;
		this.status = status;
		this.updatedAt = LocalDateTime.now();
	}
}
