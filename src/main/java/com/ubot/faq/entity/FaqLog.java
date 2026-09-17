package com.ubot.faq.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
public class FaqLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	//Todo: question_log Entity 만들어지면 객체 연결로 변경
	@Column(name = "question_log_id")
	private Long questionLogId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "faq_id")
	private Faq faq;

	@Column(name = "rank")
	private Integer rank;

	@Column(name = "similarity")
	private Double similarity;

	@Column(name = "created_at")
	private LocalDateTime createdAt;
}
