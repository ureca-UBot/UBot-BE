package com.ubot.common.dto.response;

import java.time.LocalDateTime;

import com.ubot.common.entity.AdminManagedEntity;

public record AdminMetadataResponseDto(
        Long createdBy,
        Long updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {

    public static AdminMetadataResponseDto from(AdminManagedEntity entity) {
        return new AdminMetadataResponseDto(
                entity.getCreatedBy() == null ? null : entity.getCreatedBy().getId(),
                entity.getUpdatedBy() == null ? null : entity.getUpdatedBy().getId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }
}
