package com.ubot.common.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.common.enums.MasterStatus;

import jakarta.persistence.criteria.Predicate;

public final class ProductSpecification {

    private ProductSpecification() {
    }

    public static <T extends AdminManagedEntity> Specification<T> filter(
            String keyword, MasterStatus status, Boolean deleted
    ) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (deleted != null) {
                predicates.add(deleted
                        ? builder.isNotNull(root.get("deletedAt"))
                        : builder.isNull(root.get("deletedAt")));
            }
            if (keyword != null && !keyword.isBlank()) {
                String escaped = keyword.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(builder.like(builder.lower(root.get("name")), "%" + escaped + "%", '\\'));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
