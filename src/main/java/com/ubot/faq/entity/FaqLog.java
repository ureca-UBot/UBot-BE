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
	private Integer questionLogId;

	@Column(name = "faq_id")
	private Faq faq;

	private LocalDateTime created_at;
}
