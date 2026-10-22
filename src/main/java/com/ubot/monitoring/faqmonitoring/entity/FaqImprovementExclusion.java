package com.ubot.monitoring.faqmonitoring.entity;

import com.ubot.faq.entity.Faq;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "faq_improvement_exclusion")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqImprovementExclusion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "faq_id")
	private Faq faq;

	@Enumerated(EnumType.STRING)
	@Column(name = "improvement_type")
	private FaqImprovementType improvementType;

	@Column(name = "faq_version")
	private int faqVersion;

	@Column(name = "memo")
	private String memo;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "excluded_by")
	private User excludedBy;

	@Column(name = "excluded_at")
	private LocalDateTime excludedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "released_by")
	private User releasedBy;

	@Column(name = "released_at")
	private LocalDateTime releasedAt;

	public void release(User releasedBy) {
		this.releasedBy = releasedBy;
		this.releasedAt = LocalDateTime.now();
	}
}
