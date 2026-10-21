package com.ubot.inquiry.entity;

import java.time.LocalDateTime;

import com.ubot.inquiry.enums.InquiryStatus;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "title")
    private String title;

    @Column(name = "content")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private InquiryStatus status;

    @Column(name = "admin_reply")
    private String adminReply;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public static Inquiry create(User user, String title, String content) {
        Inquiry inquiry = new Inquiry();
        inquiry.user = user;
        inquiry.title = title;
        inquiry.content = content;
        inquiry.status = InquiryStatus.RECEIVED;
        inquiry.createdAt = LocalDateTime.now();
        inquiry.updatedAt = inquiry.createdAt;
        return inquiry;
    }

    public void update(String title, String content) {
        if (title != null) this.title = title;
        if (content != null) this.content = content;
        this.updatedAt = LocalDateTime.now();
    }

    public void resolve(User administrator, String reply) {
        this.adminReply = reply;
        this.resolvedBy = administrator;
        this.status = InquiryStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
        this.updatedAt = this.resolvedAt;
    }
}
