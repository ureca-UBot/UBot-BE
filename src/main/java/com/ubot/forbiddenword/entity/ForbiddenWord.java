package com.ubot.forbiddenword.entity;

import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

// 단어 수정과 상태 변경이 동시에 일어나도 서로의 컬럼을 덮어쓰지 않도록 바뀐 컬럼만 UPDATE합니다.
@Entity
@DynamicUpdate
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

	public void updateWord(String word) {
		this.word = word;
		this.updatedAt = LocalDateTime.now();
	}

	public void updateStatus(ForbiddenWordStatus status) {
		this.status = status;
		this.updatedAt = LocalDateTime.now();
	}
}
