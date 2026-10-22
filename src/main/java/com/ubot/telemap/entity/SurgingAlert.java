package com.ubot.telemap.entity;

import java.time.LocalDateTime;

import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.enums.SurgingAlertStatus;
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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "surging_alerts")
public class SurgingAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "region")
    private RegionSido region;

    @Column(name = "subject")
    private String subject;

    @Column(name = "question_count")
    private Integer questionCount;

    @Column(name = "window_minutes")
    private Integer windowMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SurgingAlertStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
