package com.ubot.faq.entity;

import com.ubot.faq.policy.FaqQuestionNormalizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** 관리자 등록 표현과 정규화된 입력의 벡터, 승인 당시 FAQ 내용을 저장합니다. */
@Entity
@Table(name = "faq_cache_question")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqCacheQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "faq_id")
    private Faq faq;

    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "question")
    private String question;

    @Column(name = "normalized_question", nullable = false)
    private String normalizedQuestion;

    @Column(name = "normalization_version")
    private int normalizationVersion;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    @Column(name = "vector")
    private float[] vector;

    @Column(name = "faq_version")
    private int faqVersion;

    @Column(name = "faq_question")
    private String faqQuestion;

    @Column(name = "faq_answer")
    private String faqAnswer;

    @Builder.Default
    @Column(name = "enabled")
    private boolean enabled = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 이미 임베딩한 입력과 현재 FAQ 내용을 저장하고, 명시적인 재등록만 표현을 재활성화한다. */
    public void register(String question, String normalizedQuestion, float[] vector) {
        this.question = question;
        this.normalizedQuestion = normalizedQuestion;
        this.vector = vector.clone();
        this.normalizationVersion = FaqQuestionNormalizer.VERSION;
        this.faqVersion = faq.getVersion();
        this.faqQuestion = faq.getQuestion();
        this.faqAnswer = faq.getAnswer();
        this.enabled = true;
    }

    public void deactivate() {
        this.enabled = false;
    }

    @PrePersist
    @PreUpdate
    private void normalizeQuestion() {
        // 엔티티 저장도 등록·검색과 같은 Java 규칙을 사용한다.
        // 벡터의 생성 경로는 알 수 없으므로 normalizationVersion은 자동 승인하지 않는다.
        normalizedQuestion = FaqQuestionNormalizer.normalize(question);
    }
}
