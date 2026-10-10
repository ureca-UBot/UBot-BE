package com.ubot.notice.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.domain.Specification;

import com.ubot.notice.entity.Notice;
import com.ubot.notice.enums.NoticeType;
import com.ubot.ranking.enums.RegionSido;

public final class NoticeSpecification {
    private NoticeSpecification() {

    }

    public static Specification<Notice> notDeleted() {
        return (root, query, builder) -> builder.isNull(root.get("deletedAt"));
    }

    /** deleted가 null이면 삭제 여부와 상관없이 모두, true면 삭제된 항목만, false면 삭제되지 않은 항목만 조회합니다. */
    public static Specification<Notice> deleted(Boolean deleted) {
        return (root, query, builder) -> {
            if (deleted == null) {
                return builder.conjunction();
            }
            return deleted ? builder.isNotNull(root.get("deletedAt")) : builder.isNull(root.get("deletedAt"));
        };
    }

    public static Specification<Notice> hasType(NoticeType type) {
        return (root, query, builder) -> type == null
                ? builder.conjunction()
                : builder.equal(root.get("type"), type);
    }

    /** 대상 지역이 정확히 일치하는 공지만 조회합니다. null이면 조건을 걸지 않습니다. */
    public static Specification<Notice> hasTargetRegion(RegionSido region) {
        return (root, query, builder) -> region == null
                ? builder.conjunction()
                : builder.equal(root.get("targetRegion"), region);
    }

    /** 전체 공지와 해당 지역 공지를 조회합니다. region이 null이면 전체 공지만 조회합니다. */
    public static Specification<Notice> visibleIn(RegionSido region) {
        return (root, query, builder) -> region == null
                ? builder.isNull(root.get("targetRegion"))
                : builder.or(
                        builder.isNull(root.get("targetRegion")),
                        builder.equal(root.get("targetRegion"), region));
    }

    /** 노출 종료 시각이 없거나 now 이후인 공지만 조회합니다. */
    public static Specification<Notice> activeAt(LocalDateTime now) {
        return (root, query, builder) -> builder.or(
                builder.isNull(root.get("endsAt")),
                builder.greaterThan(root.get("endsAt"), now));
    }
}