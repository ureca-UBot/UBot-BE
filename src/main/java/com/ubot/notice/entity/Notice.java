package com.ubot.notice.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.notice.enums.NoticeType;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@DynamicUpdate
@Table(name = "notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice extends AdminManagedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long noticeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private NoticeType type;

    @Column(name = "title")
    private String title;

    @Column(name = "content")
    private String content;

    @Column(name = "target_region")
    private RegionSido targetRegion;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    public static Notice create(User admin, NoticeType type, String title, String content, RegionSido targetRegion,
            LocalDateTime endsAt) {
        Notice notice = new Notice();
        notice.type = type;
        notice.title = title;
        notice.content = content;
        notice.targetRegion = targetRegion;
        notice.endsAt = endsAt;
        notice.initializeMetadata(admin);
        return notice;
    }

    public void update(User admin, NoticeType type, String title, String content, RegionSido targetRegion,
            LocalDateTime endsAt) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.targetRegion = targetRegion;
        this.endsAt = endsAt;
        markUpdated(admin);
    }

    public void delete(User admin) {
        markDeleted(admin);
    }
}
