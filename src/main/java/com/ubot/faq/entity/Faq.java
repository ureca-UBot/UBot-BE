package com.ubot.faq.entity;

import com.pgvector.PGvector;
import com.ubot.faq.enums.Intent;
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

	@Builder.Default
	@Column(name = "version")
	private Integer version = 1;

	@Enumerated(EnumType.STRING)
	@Column(name = "intent")
	private Intent intent;

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
			Intent intent
	){
		this.faqCategory = category;
		this.question = question;
		this.answer = answer;
		this.intent = intent;
		this.updatedAt = LocalDateTime.now();
		this.version++;
	}

	public void delete(){
		this.deletedAt = LocalDateTime.now();
	}

	public void restore(){
		this.deletedAt = null;
	}
}