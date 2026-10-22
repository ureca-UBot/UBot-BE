package com.ubot.telemap.entity;

import java.time.LocalDateTime;

import com.ubot.chat.entity.QuestionLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "surging_questions")
public class SurgingQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_log_id")
    private QuestionLog questionLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cluster_id")
    private SurgingCluster cluster;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

	public static SurgingQuestion create(QuestionLog questionLog, SurgingCluster cluster, LocalDateTime occurredAt) {
		SurgingQuestion question = new SurgingQuestion();
		question.questionLog = questionLog;
		question.cluster = cluster;
		question.occurredAt = occurredAt;
		question.createdAt = LocalDateTime.now();
		return question;
	}
}
