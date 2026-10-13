package com.ubot.faq.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    // Flyway가 생성 컬럼의 식을 관리하며 Hibernate는 DB가 계산한 값을 읽습니다.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "normalized_question")
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
}
