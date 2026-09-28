package com.ubot.store.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ubot.store.entity.Store;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

public interface StoreJpaRepository extends JpaRepository<Store, Long>, JpaSpecificationExecutor<Store> {

    Optional<Store> findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long storeId);

    Optional<Store> findByStoreIdAndIsActiveFalseAndDeletedAtIsNotNull(Long storeId);

    Optional<Store> findByStoreNameAndAddress(String storeName, String address);

    boolean existsByStoreNameAndAddressAndStoreIdNot(
            String storeName,
            String address,
            Long storeId
    );

    @EntityGraph(attributePaths = "serviceTypes")
    List<Store> findAllByStoreIdIn(
            Collection<Long> storeIds
    );

}
