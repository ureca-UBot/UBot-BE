package com.ubot.faq.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 원본 FAQ에 연결된 변형 질문. 답변은 원본 FAQ, 벡터는 faq_embeddings에서 관리한다. */
@Entity
@Table(name = "faq_question_variants")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqQuestionVariant {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "origin_faq_id")
	private Faq originFaq;

	@Column(name = "question")
	private String question;

	@Builder.Default
	@Column(name = "created_at")
	private LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
}
