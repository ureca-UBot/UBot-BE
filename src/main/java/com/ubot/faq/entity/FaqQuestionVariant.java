package com.ubot.faq.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원본 FAQ에 연결된 변형 질문·답변. 벡터는 faq_embeddings에서 관리한다. */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqQuestionVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_faq_id", nullable = false)
    private Faq originFaq;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "question", nullable = false, length = 160)
    private String question;

    @Column(name = "answer", nullable = false)
    private String answer;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public void update(String question, String answer) {
        this.question = question;
        this.answer = answer;
        this.version++;
    }
}
