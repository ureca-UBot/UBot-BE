package com.ubot.common.entity;

import java.time.LocalDateTime;

import com.ubot.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@MappedSuperclass
@Getter
public abstract class AdminManagedEntity extends BusinessEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected void initializeMetadata(User administrator) {
        initializeTimestamps();
        this.createdBy = administrator;
        this.updatedBy = administrator;
    }

    protected void markUpdated(User administrator) {
        markUpdated();
        this.updatedBy = administrator;
    }

    protected void markDeleted(User administrator) {
        markUpdated(administrator);
        this.deletedAt = getUpdatedAt();
    }
}
