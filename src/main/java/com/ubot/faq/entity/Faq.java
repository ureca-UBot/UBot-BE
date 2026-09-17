package com.ubot.faq.entity;

import com.pgvector.PGvector;
import com.ubot.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class Faq {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private FaqCategory faqCategory;

	@Column(name = "question")
	private String question;

	@Column(name = "answer")
	private String answer;

	@Column(name = "vector")
	private PGvector vector;

	@Builder.Default
	@Column(name = "version")
	private Integer version = 1;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "admin_id")
	private User admin;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	public void update(
			FaqCategory category,
			String question,
			String answer,
			PGvector vector
	){
		this.faqCategory = category;
		this.question = question;
		this.answer = answer;
		this.vector = vector;
		this.updatedAt = LocalDateTime.now();
		this.version++;
	}

	public void delete(){
		this.deletedAt = LocalDateTime.now();
	}
}
