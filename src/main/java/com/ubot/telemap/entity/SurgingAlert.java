package com.ubot.telemap.entity;

import java.time.LocalDateTime;
import java.util.List;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_snapshots")
    private List<SurgingQuestionSnapshot> questionSnapshots;

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

    public static SurgingAlert create(
            RegionSido region,
            List<SurgingQuestionSnapshot> questionSnapshots,
            int questionCount,
            int windowMinutes
    ) {
        SurgingAlert alert = new SurgingAlert();
        alert.region = region;
        alert.questionSnapshots = List.copyOf(questionSnapshots);
        alert.questionCount = questionCount;
        alert.windowMinutes = windowMinutes;
        alert.status = SurgingAlertStatus.PENDING;
        alert.createdAt = LocalDateTime.now();
        alert.updatedAt = alert.createdAt;
        return alert;
    }

    public static SurgingAlert create(
            RegionSido region,
            List<SurgingQuestionSnapshot> questionSnapshots,
            int windowMinutes
    ) {
        return create(region, questionSnapshots, questionSnapshots.size(), windowMinutes);
    }

    public void updateSubject(String subject) {
        this.subject = subject;
        this.updatedAt = LocalDateTime.now();
    }
}
