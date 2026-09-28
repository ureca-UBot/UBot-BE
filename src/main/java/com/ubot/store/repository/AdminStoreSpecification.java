package com.ubot.store.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import com.ubot.store.entity.ServiceType;
import com.ubot.store.entity.Store;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

@RequiredArgsConstructor
public final class AdminStoreSpecification {

    public static Specification<Store> filter(
            String storeName,
            String phoneNumber,
            Set<String> serviceCodes
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(criteriaBuilder.isTrue(root.get("isActive")));

            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            if (storeName != null) {
                predicates.add(
                        criteriaBuilder.like(
                                criteriaBuilder.lower(root.get("storeName")),
                                "%" + storeName.toLowerCase(Locale.ROOT) + "%"
                        )
                );
            }

            if (phoneNumber != null) {
                predicates.add(
                        criteriaBuilder.like(
                                root.get("phoneNumber"),
                                "%" + phoneNumber + "%"
                        )
                );
            }

            if (!serviceCodes.isEmpty()) {
                Subquery<Long> serviceCount = query.subquery(Long.class);

                Root<Store> subStore = serviceCount.from(Store.class);

                Join<Store, ServiceType> serviceJoin = subStore.join("serviceTypes");

                serviceCount.select(criteriaBuilder.countDistinct(serviceJoin.get("serviceCode")));

                serviceCount.where(
                        criteriaBuilder.equal(
                                subStore.get("storeId"),
                                root.get("storeId")
                        ),
                        serviceJoin.get("serviceCode").in(serviceCodes),
                        criteriaBuilder.isTrue(
                                serviceJoin.get("isActive")
                        )
                );

                predicates.add(criteriaBuilder.equal(serviceCount, (long) serviceCodes.size()));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}