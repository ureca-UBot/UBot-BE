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
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "faq_category_id")
	private  FaqCategory faqCategory;

	private String question;

	private String answer;

	private PGvector vector;

	@Builder.Default
	private Integer version = 1;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "admin_id")
	private User admin;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

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
