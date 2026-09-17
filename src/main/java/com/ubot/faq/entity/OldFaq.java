package com.ubot.faq.entity;

import com.pgvector.PGvector;
import com.ubot.faq.entity.id.OldFaqId;
import com.ubot.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@IdClass(OldFaqId.class)
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class OldFaq {
	@Id
	@Column(name = "faq_id", nullable = false, length = 255)
	private Long faqId;

	@Id
	@Column(name = "version")
	private Integer version;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "faq_category_id")
	private  FaqCategory faqCategory;

	private String question;

	private String answer;

	private PGvector vector;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by")
	private User createdBy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "updated_by")
	private User updatedBy;

	private LocalDateTime updatedAt;



	public static OldFaq from(Faq faq, User updateAdmin){
		return OldFaq.builder()
				.faqId(faq.getId())
				.version(faq.getVersion())
				.faqCategory(faq.getFaqCategory())
				.question(faq.getQuestion())
				.answer(faq.getAnswer())
				.vector(faq.getVector())
				.createdBy(faq.getAdmin())
				.updatedBy(updateAdmin)
				.updatedAt(LocalDateTime.now())
				.build();
	}
}
